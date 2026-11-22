package com.vn.vitalcare.it;

import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

/**
 * FE-11 Caregiver Data Sharing, FE-13 Notifications and FE-21 Emergency end to end: the patient grants a family
 * member access (SharingController), the caregiver reads only what is shared and loses it on revoke (BR-23), a
 * critical alert reaches the caregiver as an emergency notice (AlertNotifications, EmergencyController), and the
 * notification centre (NotificationController) keeps each account's notices to itself.
 */
class SharingIT extends CommittedIntegrationTest {

    private record Caregiver(String phone, String token) {
    }

    private Caregiver caregiver() throws Exception {
        String phone = newPhone();
        return new Caregiver(phone, register(phone, "Nguoi Than", "Passw0rd!"));
    }

    private String grant(String patientToken, Caregiver c, String extra) throws Exception {
        return json(callJson(patientToken, post("/api/my_sharing"), """
                {"caregiverPhone":"%s","emergencyPhone":"0912345678","note":"Con gái"%s}""".formatted(c.phone(), extra))
                .andExpect(status().is2xxSuccessful()), "$.id");
    }

    @Test
    void shr01_caregiverSeesThePatientAndTheirLatestVitals() throws Exception {
        Monitored m = monitoredPatient();
        upload(m, "{\"clientRecordId\":\"s1\",\"measuredAt\":\"%s\",\"spo2\":97,\"heartRate\":72}".formatted(soon(0)));
        String own = tokenFor(m.phone(), "Passw0rd!");
        Caregiver c = caregiver();
        grant(own, c, "");
        call(c.token(), get("/api/shared_patients")).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].customerId", hasItem(m.customerId().intValue())));
        call(c.token(), get("/api/shared_patients/" + m.customerId() + "/vitals/latest")).andExpect(status().isOk())
                .andExpect(jsonPath("$.heartRate").value(72));
        assertEquals(1, count("select count(*) from sharing_permission where customer_id = ? and status = 'ACTIVE'", m.customerId()));
    }

    /** Appointments are not shared unless the patient says so. */
    @Test
    void shr02_appointmentsOnlyOnceShared() throws Exception {
        String phone = newPhone();
        Long patient = patient(phone);
        String own = tokenFor(phone, "Passw0rd!");
        Caregiver c = caregiver();
        Long id = Long.valueOf(grant(own, c, ""));
        call(c.token(), get("/api/shared_patients/" + patient + "/appointments")).andExpect(status().isNotFound());
        callJson(own, put("/api/my_sharing/" + id), "{\"shareAppointments\":true}").andExpect(status().isOk());
        call(c.token(), get("/api/shared_patients/" + patient + "/appointments")).andExpect(status().isOk());
    }

    /** BR-23: revoking ends the caregiver's access at once; revoking twice is refused. */
    @Test
    void shr03_revokeEndsAccessAtOnce() throws Exception {
        String phone = newPhone();
        Long patient = patient(phone);
        String own = tokenFor(phone, "Passw0rd!");
        Caregiver c = caregiver();
        String id = grant(own, c, "");
        call(own, post("/api/my_sharing/" + id + "/revoke")).andExpect(status().isOk());
        call(c.token(), get("/api/shared_patients/" + patient + "/vitals/latest")).andExpect(status().isNotFound());
        call(own, post("/api/my_sharing/" + id + "/revoke")).andExpect(status().isConflict());
        assertEquals(1, count("select count(*) from sharing_permission where id = ? and status = 'REVOKED' and revoked_at is not null",
                Long.valueOf(id)));
    }

    @Test
    void shr04_numberWithoutAccountOwnNumberAndPastExpiryRefused() throws Exception {
        String phone = newPhone();
        patient(phone);
        String own = tokenFor(phone, "Passw0rd!");
        callJson(own, post("/api/my_sharing"), """
                {"caregiverPhone":"%s","emergencyPhone":"0912345678"}""".formatted(newPhone())).andExpect(status().isUnprocessableContent());
        callJson(own, post("/api/my_sharing"), """
                {"caregiverPhone":"%s","emergencyPhone":"0912345678"}""".formatted(phone)).andExpect(status().isUnprocessableContent());
        callJson(own, post("/api/my_sharing"), """
                {"caregiverPhone":"%s","emergencyPhone":"0912345678","expiresAt":"2020-01-01T00:00:00Z"}""".formatted(caregiver().phone()))
                .andExpect(status().isUnprocessableContent());
    }

    /** BF-06: at most two caregivers at a time. */
    @Test
    void shr05_atMostTwoCaregivers() throws Exception {
        String phone = newPhone();
        patient(phone);
        String own = tokenFor(phone, "Passw0rd!");
        grant(own, caregiver(), "");
        grant(own, caregiver(), "");
        callJson(own, post("/api/my_sharing"), """
                {"caregiverPhone":"%s","emergencyPhone":"0912345678"}""".formatted(caregiver().phone())).andExpect(status().isConflict());
        call(own, get("/api/my_sharing")).andExpect(jsonPath("$.length()").value(2));
    }

    /** FE-21: a critical alert reaches the caregiver it is shared with as an emergency notice they acknowledge. */
    @Test
    void shr06_criticalAlertReachesTheCaregiverAsAnEmergency() throws Exception {
        Monitored m = monitoredPatient();
        String own = tokenFor(m.phone(), "Passw0rd!");
        Caregiver c = caregiver();
        grant(own, c, ",\"shareAlerts\":true");
        upload(m, "{\"clientRecordId\":\"e1\",\"measuredAt\":\"%s\",\"spo2\":85,\"heartRate\":80}".formatted(soon(0)))
                .andExpect(status().isOk());
        String notices = call(c.token(), get("/api/emergency_alerts")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(notices.contains("\"id\""), notices);
        String notice = json(call(c.token(), get("/api/emergency_alerts")), "$[0].id");
        call(c.token(), post("/api/emergency_alerts/" + notice + "/acknowledge")).andExpect(status().isOk());
        assertTrue(count("select count(*) from notification n join users u on u.id = n.user_id where u.phone = ?", intl(c.phone())) >= 1);
    }

    /** FE-13: a booking notifies the patient; their notices are theirs alone; read, read all, remove. */
    @Test
    void shr07_notificationCentreKeepsEachAccountsNotices() throws Exception {
        freshDoctor();
        String phone = newPhone();
        patient(phone);
        String own = tokenFor(phone, "Passw0rd!");
        paidVisit(own);
        Thread.sleep(300);
        String first = json(call(own, get("/api/notifications")).andExpect(status().isOk()), "$[0].id");
        assertTrue(Integer.parseInt(json(call(own, get("/api/notifications/unread-count")), "$.unread")) >= 1);
        call(own, post("/api/notifications/" + first + "/read")).andExpect(status().isOk()).andExpect(jsonPath("$.readAt").isNotEmpty());
        call(tokenOf(DEMO_PATIENT), get("/api/notifications/" + first)).andExpect(status().isNotFound());
        call(own, post("/api/notifications/read-all")).andExpect(status().isOk());
        assertEquals("0", json(call(own, get("/api/notifications/unread-count")), "$.unread"));
        call(own, delete("/api/notifications/" + first)).andExpect(status().is2xxSuccessful());
        assertEquals(1, count("select count(*) from notification where id = ? and deleted_at is not null", Long.valueOf(first)));
    }

    @Test
    void shr08_deskSeesThePatientsCaregivers() throws Exception {
        String phone = newPhone();
        Long patient = patient(phone);
        Caregiver c = caregiver();
        grant(tokenFor(phone, "Passw0rd!"), c, "");
        call(tokenOf(DESK_1), get("/api/customers/" + patient + "/caregivers")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void shr09_strangerCannotReadAPatientNotSharedWithThem() throws Exception {
        Long patient = patient(newPhone());
        call(caregiver().token(), get("/api/shared_patients/" + patient + "/vitals/latest")).andExpect(status().isNotFound());
        call(caregiver().token(), get("/api/my_sharing")).andExpect(status().isForbidden());
    }
}
