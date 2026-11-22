package com.vn.vitalcare.it;

import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * Device sync history and the readings a device could not hand in (SyncController → sync_batch, sync_failure; retry
 * runs the reading through VitalIngestion again), the broker's hooks (DeviceDataController /api/mqtt/auth and /acl,
 * EMQX signs in with the hook secret), and the staff views of an alert: the clinic summary, the readings around it
 * (/live) and the calls placed for it (/api/escalations).
 */
@TestPropertySource(properties = "app.mqtt.hook-secret=it-hook-secret")
class SyncIT extends CommittedIntegrationTest {

    private static String reading(String id, String at, int spo2) {
        return """
                {"clientRecordId":"%s","measuredAt":"%s","spo2":%d,"heartRate":80,"skinTemperature":36.4}""".formatted(id, at, spo2);
    }

    private static String hoursAhead(int hours) {
        return java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).plusHours(hours).withNano(0).toString();
    }

    private Long batchOf(Monitored m) {
        return jdbc.queryForObject("select id from sync_batch where device_id = ? order by id desc limit 1", Long.class, m.deviceId());
    }

    private Long failureOf(Monitored m) {
        return jdbc.queryForObject("select id from sync_failure where device_id = ? order by id desc limit 1", Long.class, m.deviceId());
    }

    // ---------------------------------------------------------------- sync history and failures
    @Test
    void syn01_batchIsRecordedWithItsCounts_refusedReadingBecomesAFailure() throws Exception {
        Monitored m = monitoredPatient();
        upload(m, reading("ok-1", soon(0), 97), reading("fut-1", hoursAhead(2), 97)).andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(1)).andExpect(jsonPath("$.rejected").value(1));
        Long batch = batchOf(m);
        String doctor = tokenOf(DOCTOR_1);
        call(doctor, get("/api/device_syncs/" + batch)).andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceCode").value(m.deviceCode()))
                .andExpect(jsonPath("$.recordCount").value(2))
                .andExpect(jsonPath("$.acceptedCount").value(1))
                .andExpect(jsonPath("$.rejectedCount").value(1));
        call(doctor, get("/api/device_syncs/" + batch + "/failures")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].clientRecordId").value("fut-1"))
                .andExpect(jsonPath("$[0].status").value("OPEN"));
        call(doctor, get("/api/device_syncs")).andExpect(status().isOk());
        call(doctor, get("/api/sync_failures")).andExpect(status().isOk());
    }

    /** Retrying a reading that is still from the future leaves it OPEN with one more attempt (the refusal on upload was the first). */
    @Test
    void syn02_retryOfAStillBadReadingStaysOpen() throws Exception {
        Monitored m = monitoredPatient();
        upload(m, reading("fut-2", hoursAhead(3), 97)).andExpect(jsonPath("$.rejected").value(1));
        Long failure = failureOf(m);
        String desk = tokenOf(DESK_1);
        call(desk, post("/api/sync_failures/" + failure + "/retry")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.attempts").value(2));
    }

    /** A known-wrong reading is set aside once; doing it again is a conflict, and a doctor only reads. */
    @Test
    void syn03_discardOnce_andOnlyByWhoMayWrite() throws Exception {
        Monitored m = monitoredPatient();
        upload(m, reading("fut-3", hoursAhead(2), 97));
        Long failure = failureOf(m);
        call(tokenOf(DOCTOR_1), post("/api/sync_failures/" + failure + "/discard")).andExpect(status().isForbidden());
        String desk = tokenOf(DESK_1);
        call(desk, post("/api/sync_failures/" + failure + "/discard")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISCARDED"));
        call(desk, post("/api/sync_failures/" + failure + "/discard")).andExpect(status().isConflict());
    }

    @Test
    void syn04_anotherClinicCannotSeeTheSync() throws Exception {
        Monitored m = monitoredPatient();
        upload(m, reading("fut-4", hoursAhead(2), 97));
        String doctor2 = tokenOf(DOCTOR_2);
        call(doctor2, get("/api/device_syncs/" + batchOf(m))).andExpect(status().isNotFound());
        call(doctor2, get("/api/sync_failures/" + failureOf(m))).andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- broker hooks
    private org.springframework.test.web.servlet.ResultActions hook(String path, String secret, String body) throws Exception {
        var request = post(path).contentType(MediaType.APPLICATION_JSON).content(body);
        if (secret != null) {
            request.header("X-Hook-Secret", secret);
        }
        return mvc.perform(request);
    }

    /** Only the broker (with the hook secret) may ask; a device signs in with its code and its own token. */
    @Test
    void syn05_mqttAuthentication() throws Exception {
        Monitored m = monitoredPatient();
        String ok = "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(m.deviceCode(), m.deviceToken());
        hook("/api/mqtt/auth", null, ok).andExpect(status().isForbidden());
        hook("/api/mqtt/auth", "wrong-secret", ok).andExpect(status().isForbidden());
        hook("/api/mqtt/auth", "it-hook-secret", ok).andExpect(status().isOk()).andExpect(jsonPath("$.result").value("allow"));
        hook("/api/mqtt/auth", "it-hook-secret", "{\"username\":\"%s\",\"password\":\"not-the-token\"}".formatted(m.deviceCode()))
                .andExpect(jsonPath("$.result").value("deny"));
    }

    /** A device publishes only under its own code and subscribes only to its own acks. */
    @Test
    void syn06_mqttAclKeepsEachDeviceToItsOwnTopics() throws Exception {
        String code = "IT-" + newPhone().substring(3);
        String own = "vitalcare/devices/" + code + "/";
        String acl = "{\"username\":\"%s\",\"topic\":\"%s\",\"action\":\"%s\"}";
        hook("/api/mqtt/acl", "it-hook-secret", acl.formatted(code, own + "vitals", "publish")).andExpect(jsonPath("$.result").value("allow"));
        hook("/api/mqtt/acl", "it-hook-secret", acl.formatted(code, own + "ack", "subscribe")).andExpect(jsonPath("$.result").value("allow"));
        hook("/api/mqtt/acl", "it-hook-secret", acl.formatted(code, "vitalcare/devices/IT-OTHER/vitals", "publish"))
                .andExpect(jsonPath("$.result").value("deny"));
        hook("/api/mqtt/acl", "it-hook-secret", acl.formatted(code, own + "vitals", "subscribe")).andExpect(jsonPath("$.result").value("deny"));
    }

    // ---------------------------------------------------------------- alert views for staff
    @Test
    void syn07_alertSummaryLiveReadingsAndEscalations() throws Exception {
        Monitored m = monitoredPatient();
        upload(m, reading("low-1", soon(0), 88)).andExpect(status().isOk());
        Long alert = jdbc.queryForObject("select id from alert where customer_id = ? order by id desc limit 1", Long.class, m.customerId());
        String doctor = tokenOf(DOCTOR_1);
        call(doctor, get("/api/alerts/summary")).andExpect(status().isOk());
        call(doctor, get("/api/alerts/" + alert + "/live")).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].spo2", hasItem(88.0)));
        call(doctor, get("/api/escalations").param("alertId", alert.toString())).andExpect(status().isOk());
        call(tokenOf(DOCTOR_2), get("/api/escalations").param("alertId", alert.toString())).andExpect(status().isNotFound());
        assertEquals(1, count("select count(*) from alert where id = ?", alert));
    }
}
