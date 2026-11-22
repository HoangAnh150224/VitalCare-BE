package com.vn.vitalcare.it;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * FE-03 → FE-06 vital signs end to end: a band's upload (DeviceDataController → VitalIngestion) is stored in
 * vital_signs and read back by staff (VitalSignController: latest, history, trend, details) and by the patient
 * (MyVitalSignController), with duplicates, implausible values, out-of-range clocks, clinic scope and the
 * audit of who viewed health data (BR-24).
 */
class VitalSignIT extends CommittedIntegrationTest {

    private String doctor;
    /** Readings are stamped just after the band was handed out; the window must reach past now. */
    private final String until = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).plusMinutes(10).withNano(0).toString();

    @BeforeEach
    void staff() throws Exception {
        doctor = tokenOf(DOCTOR_1);
    }

    private static String reading(String id, String at, Object spo2, Object heartRate, Object skin) {
        return """
                {"clientRecordId":"%s","measuredAt":"%s","spo2":%s,"heartRate":%s,"skinTemperature":%s}"""
                .formatted(id, at, spo2, heartRate, skin);
    }

    @Test
    void vit01_uploadIsStoredAndShownAsTheLatestToStaffAndPatient() throws Exception {
        Monitored m = monitoredPatient();
        upload(m, reading("r1", soon(0), 97, 76, 36.4), reading("r2", soon(5), 96, 80, 36.5))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(2))
                .andExpect(jsonPath("$.duplicates").value(0));
        assertEquals(2, count("select count(*) from vital_signs where customer_id = ? and validation_status = 'VALID'", m.customerId()));
        call(doctor, get("/api/vital_signs/latest").param("customerId", m.customerId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.heartRate").value(80))
                .andExpect(jsonPath("$.spo2").value(96.0));
        String own = tokenFor(m.phone(), "Passw0rd!");
        call(own, get("/api/my_vital_signs/latest")).andExpect(status().isOk()).andExpect(jsonPath("$.heartRate").value(80));
    }

    /** Offline Data Queue: a reading sent again (same clientRecordId) is counted as a duplicate and stored once. */
    @Test
    void vit02_resentReadingIsStoredOnce() throws Exception {
        Monitored m = monitoredPatient();
        String r = reading("dup-1", soon(0), 97, 76, 36.4);
        upload(m, r).andExpect(jsonPath("$.accepted").value(1));
        upload(m, r).andExpect(status().isOk()).andExpect(jsonPath("$.duplicates").value(1)).andExpect(jsonPath("$.accepted").value(0));
        assertEquals(1, count("select count(*) from vital_signs where customer_id = ?", m.customerId()));
    }

    /** An implausible value is kept as INVALID with its reason, and left out of the default history. */
    @Test
    void vit03_implausibleValueIsStoredInvalidAndHiddenByDefault() throws Exception {
        Monitored m = monitoredPatient();
        upload(m, reading("bad-1", soon(0), 30, 76, 36.4)).andExpect(status().isOk()).andExpect(jsonPath("$.invalid").value(1));
        assertEquals(1, count("select count(*) from vital_signs where customer_id = ? and validation_status = 'INVALID' "
                + "and validation_error is not null", m.customerId()));
        call(doctor, get("/api/vital_signs").param("customerId", m.customerId().toString()).param("to", until))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        call(doctor, get("/api/vital_signs").param("customerId", m.customerId().toString()).param("to", until).param("validation", "all"))
                .andExpect(jsonPath("$[0].validationStatus").value("INVALID"));
    }

    /** A reading from beyond the device-clock tolerance (5 minutes ahead) is refused and kept as a sync failure. */
    @Test
    void vit04_readingFromTheFutureIsRefused() throws Exception {
        Monitored m = monitoredPatient();
        String later = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).plusHours(2).withNano(0).toString();
        upload(m, reading("fut-1", later, 97, 76, 36.4)).andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected").value(1)).andExpect(jsonPath("$.accepted").value(0));
        assertEquals(0, count("select count(*) from vital_signs where customer_id = ?", m.customerId()));
    }

    @Test
    void vit05_historyIsNewestFirstWithTotal_andTrendAggregates() throws Exception {
        Monitored m = monitoredPatient();
        upload(m, reading("h1", soon(0), 97, 70, 36.4), reading("h2", soon(10), 95, 90, 36.6), reading("h3", soon(20), 96, 80, 36.5))
                .andExpect(jsonPath("$.accepted").value(3));
        call(doctor, get("/api/vital_signs").param("customerId", m.customerId().toString()).param("to", until))
                .andExpect(header().string("X-Total-Count", "3"))
                .andExpect(jsonPath("$[0].heartRate").value(80))
                .andExpect(jsonPath("$[2].heartRate").value(70));
        String trend = call(doctor, get("/api/vital_signs/trend").param("customerId", m.customerId().toString()).param("to", until))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(trend.contains("\"heartRateMax\":90") && trend.contains("\"heartRateMin\":70"), trend);
    }

    /** Health data is protected: reading a patient's history leaves an audit row naming who looked (BR-24). */
    @Test
    void vit06_viewingHistoryIsAudited() throws Exception {
        Monitored m = monitoredPatient();
        upload(m, reading("a1", soon(0), 97, 76, 36.4));
        int before = count("select count(*) from audit_log where entity_id = ?", m.customerId());
        call(doctor, get("/api/vital_signs").param("customerId", m.customerId().toString()).param("to", until)).andExpect(status().isOk());
        assertTrue(count("select count(*) from audit_log where entity_id = ?", m.customerId()) > before);
    }

    @Test
    void vit07_otherClinicStaffCannotReadThePatientsVitals() throws Exception {
        Monitored m = monitoredPatient();
        upload(m, reading("s1", soon(0), 97, 76, 36.4));
        call(tokenOf(DOCTOR_2), get("/api/vital_signs/latest").param("customerId", m.customerId().toString()))
                .andExpect(status().isNotFound());
        call(doctor, get("/api/vital_signs/overview")).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].customerId", hasItem(m.customerId().intValue())));
        call(tokenOf(DOCTOR_2), get("/api/vital_signs/overview"))
                .andExpect(jsonPath("$[*].customerId", not(hasItem(m.customerId().intValue()))));
    }

    @Test
    void vit08_patientReadsOnlyTheirOwnMeasurement() throws Exception {
        Monitored m = monitoredPatient();
        upload(m, reading("o1", soon(0), 97, 76, 36.4));
        Long id = jdbc.queryForObject("select id from vital_signs where customer_id = ?", Long.class, m.customerId());
        call(tokenFor(m.phone(), "Passw0rd!"), get("/api/my_vital_signs/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.heartRate").value(76)).andExpect(jsonPath("$.deviceCode").value(m.deviceCode()));
        call(tokenOf(DEMO_PATIENT), get("/api/my_vital_signs/" + id)).andExpect(status().isNotFound());
    }

    /** The band's own CSV log (text/csv) goes through the same ingestion. */
    @Test
    void vit09_bandLogCsvIsAccepted() throws Exception {
        Monitored m = monitoredPatient();
        long ts = java.time.Instant.now().plusSeconds(40).getEpochSecond();
        String csv = "#v2\nts,t100,hr,spo2,conf,flags\n" + ts + ",3640,78,97,98,0\n" + (ts + 5) + ",3650,79,96,98,0\n";
        mvc.perform(post("/api/device_data/batch").header("X-Device-Code", m.deviceCode()).header("X-Device-Token", m.deviceToken())
                        .contentType(MediaType.parseMediaType("text/csv")).content(csv))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(2));
        assertEquals(2, count("select count(*) from vital_signs where customer_id = ? and heart_rate in (78, 79)", m.customerId()));
    }
}
