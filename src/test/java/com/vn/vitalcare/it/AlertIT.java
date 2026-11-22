package com.vn.vitalcare.it;

import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * FE-19 Alert Rule Configuration / Alert Handling and FE-20 Audit end to end: an abnormal reading from the band
 * raises an alert by the clinic's rules (AlertEngine), staff handle it (acknowledge, note, resolve, dismiss), a
 * patient's own rule replaces the clinic's (BR-21), and every handling step is in the alert and audit tables.
 *
 * <p>Alerts are raised after the reading is committed (AlertEngine is an AFTER_COMMIT listener), so this suite runs
 * without the rollback transaction: each test commits like the real system, on fresh patients and devices of its own.
 */
class AlertIT extends CommittedIntegrationTest {

    private String doctor;

    @BeforeEach
    void staff() throws Exception {
        doctor = tokenOf(DOCTOR_1);
    }

    private static String spo2(String id, String at, int value) {
        return """
                {"clientRecordId":"%s","measuredAt":"%s","spo2":%d,"heartRate":80,"skinTemperature":36.4}""".formatted(id, at, value);
    }

    private Long openAlert(Monitored m) {
        return jdbc.queryForObject("select id from alert where customer_id = ? order by id desc limit 1", Long.class, m.customerId());
    }

    @Test
    void alr01_lowSpo2RaisesACriticalAlertForThePatient() throws Exception {
        Monitored m = monitoredPatient();
        upload(m, spo2("l1", soon(0), 88)).andExpect(status().isOk());
        Long alert = openAlert(m);
        call(doctor, get("/api/alerts/" + alert)).andExpect(status().isOk())
                .andExpect(jsonPath("$.alert.severity").value("CRITICAL"))
                .andExpect(jsonPath("$.alert.metric").value("SPO2"))
                .andExpect(jsonPath("$.alert.measuredValue").value(88.0))
                .andExpect(jsonPath("$.alert.status").value("OPEN"));
        call(doctor, get("/api/alerts")).andExpect(jsonPath("$[*].id", hasItem(alert.intValue())));
    }

    @Test
    void alr02_normalReadingRaisesNothing() throws Exception {
        Monitored m = monitoredPatient();
        upload(m, spo2("n1", soon(0), 98)).andExpect(status().isOk());
        assertEquals(0, count("select count(*) from alert where customer_id = ?", m.customerId()));
    }

    /** Another abnormal reading of the same measure joins the open alert instead of raising a new one. */
    @Test
    void alr03_repeatedAbnormalReadingsMergeIntoTheOpenAlert() throws Exception {
        Monitored m = monitoredPatient();
        upload(m, spo2("m1", soon(0), 93), spo2("m2", soon(10), 92)).andExpect(status().isOk());
        assertEquals(1, count("select count(*) from alert where customer_id = ? and metric = 'SPO2'", m.customerId()));
        assertTrue(count("select occurrences from alert where customer_id = ?", m.customerId()) >= 2);
    }

    @Test
    void alr04_acknowledgeOnce() throws Exception {
        Monitored m = monitoredPatient();
        upload(m, spo2("k1", soon(0), 88));
        Long alert = openAlert(m);
        call(doctor, post("/api/alerts/" + alert + "/acknowledge")).andExpect(status().isOk())
                .andExpect(jsonPath("$.alert.status").value("ACKNOWLEDGED"))
                .andExpect(jsonPath("$.alert.acknowledgedAt").isNotEmpty());
        call(doctor, post("/api/alerts/" + alert + "/acknowledge")).andExpect(status().isConflict());
    }

    @Test
    void alr05_resolveNeedsHandlingNotes() throws Exception {
        Monitored m = monitoredPatient();
        upload(m, spo2("v1", soon(0), 88));
        Long alert = openAlert(m);
        callJson(doctor, post("/api/alerts/" + alert + "/resolve"), "{\"note\":\" \"}").andExpect(status().is4xxClientError());
        callJson(doctor, post("/api/alerts/" + alert + "/resolve"), "{\"note\":\"Đã gọi bệnh nhân, SpO2 hồi phục\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.alert.status").value("RESOLVED"));
        assertEquals(1, count("select count(*) from alert where id = ? and status = 'RESOLVED' and handling_notes like 'Đã gọi%'", alert));
    }

    @Test
    void alr06_dismissNeedsAReason_thenTheAlertIsClosed() throws Exception {
        Monitored m = monitoredPatient();
        upload(m, spo2("d1", soon(0), 88));
        Long alert = openAlert(m);
        callJson(doctor, post("/api/alerts/" + alert + "/dismiss"), "{\"note\":\"\"}").andExpect(status().is4xxClientError());
        callJson(doctor, post("/api/alerts/" + alert + "/dismiss"), "{\"note\":\"Đeo lỏng vòng\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.alert.status").value("DISMISSED"));
        call(doctor, post("/api/alerts/" + alert + "/acknowledge")).andExpect(status().isConflict());
    }

    /** BR-21: the patient's own rule replaces the clinic's for that measure. */
    @Test
    void alr07_patientRuleReplacesTheClinicRule() throws Exception {
        Monitored m = monitoredPatient();
        callJson(doctor, post("/api/alert_rules"), """
                {"name":"SpO₂ riêng","metric":"SPO2","operator":"BELOW","threshold":92,"severity":"WARNING","enabled":true,
                 "note":"Bệnh nhân COPD, nền SpO2 thấp","customerId":%d}""".formatted(m.customerId()))
                .andExpect(status().isCreated());
        upload(m, spo2("p1", soon(0), 93)).andExpect(status().isOk());
        assertEquals(0, count("select count(*) from alert where customer_id = ?", m.customerId()),
                "93 is under the clinic's 94 but above the patient's own 92");
        upload(m, spo2("p2", soon(10), 91)).andExpect(status().isOk());
        assertEquals(1, count("select count(*) from alert where customer_id = ?", m.customerId()));
    }

    @Test
    void alr08_ruleValidation_andTheDeskCannotWriteRules() throws Exception {
        callJson(doctor, post("/api/alert_rules"), """
                {"name":"","metric":"WEIGHT","operator":"BELOW","threshold":1,"severity":"WARNING","enabled":true,"note":"x"}""")
                .andExpect(status().isUnprocessableContent());
        callJson(tokenOf(DESK_1), post("/api/alert_rules"), """
                {"name":"Thử","metric":"SPO2","operator":"BELOW","threshold":92,"severity":"WARNING","enabled":true,"note":"x"}""")
                .andExpect(status().isForbidden());
    }

    @Test
    void alr09_patientSeesOwnAlert_andHandlingIsAudited() throws Exception {
        Monitored m = monitoredPatient();
        upload(m, spo2("a1", soon(0), 88));
        Long alert = openAlert(m);
        call(tokenFor(m.phone(), "Passw0rd!"), get("/api/my_alerts")).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(alert.intValue())));
        call(doctor, post("/api/alerts/" + alert + "/acknowledge")).andExpect(status().isOk());
        assertTrue(count("select count(*) from audit_log where entity_type = 'alerts' and entity_id = ?", alert) >= 1);
        call(tokenOf(ADMIN), get("/api/audit_logs").param("entityType", "alerts")).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].entityId", hasItem(alert.intValue())));
    }
}
