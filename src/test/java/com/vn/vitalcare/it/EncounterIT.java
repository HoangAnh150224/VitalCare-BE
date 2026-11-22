package com.vn.vitalcare.it;

import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * FE-18 Clinical Encounter and FE-22 Service Orders / Results end to end: the doctor's encounter and its conclusion
 * rules (ClinicalEncounterController), services ordered with their own bill (BF-03), done by a technician only once
 * paid, the result reviewed by the doctor before the visit is concluded (ServiceOrderController), the patient's visit
 * result (MyVisitResultController) and a result-return appointment (ResultsController).
 */
class EncounterIT extends CommittedIntegrationTest {

    private static final long XRAY = 4L; // XQ-NG X-quang ngực thẳng, 180.000 đ, 45 minutes
    private Doctor doctor;
    private String desk;

    @BeforeEach
    void staff() throws Exception {
        doctor = freshDoctor();
        desk = tokenOf(DESK_1);
    }

    private record Visit(String phone, Long customerId, Long appointmentId, Long encounterId) {
    }

    private Visit inExamination() throws Exception {
        String phone = newPhone();
        Long customer = patient(phone);
        Long visit = paidVisit(tokenFor(phone, "Passw0rd!"));
        return new Visit(phone, customer, visit, encounter(doctor, customer, visit));
    }

    private String technician() throws Exception {
        String phone = newPhone();
        callJson(tokenOf(CLINIC_ADMIN_1), post("/api/employees"), """
                {"phone":"%s","fullName":"Kỹ Thuật Viên","password":"Passw0rd!","staffType":"technician","clinicId":"%s"}"""
                .formatted(phone, CLINIC_1)).andExpect(status().isCreated());
        return tokenFor(phone, "Passw0rd!");
    }

    private Long order(Visit v) throws Exception {
        return Long.valueOf(json(callJson(doctor.token(), post("/api/clinical_encounters/" + v.encounterId() + "/service_orders"),
                "{\"serviceId\":%d,\"instruction\":\"Chụp thẳng\"}".formatted(XRAY)).andExpect(status().isCreated()), "$.id"));
    }

    private void payOrder(Long order) throws Exception {
        Long invoice = jdbc.queryForObject("select invoice_id from invoice_item where service_order_id = ? and deleted_at is null", Long.class, order);
        callJson(desk, post("/api/invoices/" + invoice + "/payments"), "{\"method\":\"CASH\"}").andExpect(status().isOk());
    }

    @Test
    void enc01_doctorRecordsFindingsAndVitals() throws Exception {
        Visit v = inExamination();
        callJson(doctor.token(), patch("/api/clinical_encounters/" + v.encounterId()), """
                {"chiefComplaint":"Hồi hộp","examinationFindings":"Tim đều, không âm thổi","diagnosis":"Tăng huyết áp độ 1",
                 "vitals":{"temperature":36.8,"pulse":88}}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.examinationFindings").value("Tim đều, không âm thổi"))
                .andExpect(jsonPath("$.vitals.pulse").value(88));
        assertEquals(1, count("select count(*) from clinical_encounter where id = ? and status = 'IN_PROGRESS'", v.encounterId()));
    }

    /** Concluding is signing the visit off: not without a diagnosis, and only once. */
    @Test
    void enc02_conclusionNeedsADiagnosisAndHappensOnce() throws Exception {
        String phone = newPhone();
        Long customer = patient(phone);
        Long visit = paidVisit(tokenFor(phone, "Passw0rd!"));
        Long enc = Long.valueOf(json(callJson(doctor.token(), post("/api/clinical_encounters"), """
                {"customerId":%d,"appointmentId":%d,"chiefComplaint":"Ho"}""".formatted(customer, visit))
                .andExpect(status().isCreated()), "$.id"));
        call(doctor.token(), post("/api/clinical_encounters/" + enc + "/complete")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Write the diagnosis before concluding the visit"));
        callJson(doctor.token(), patch("/api/clinical_encounters/" + enc), "{\"chiefComplaint\":\"Ho\",\"diagnosis\":\"Viêm họng cấp\"}")
                .andExpect(status().isOk());
        call(doctor.token(), post("/api/clinical_encounters/" + enc + "/complete")).andExpect(status().isOk());
        call(doctor.token(), post("/api/clinical_encounters/" + enc + "/complete")).andExpect(status().isConflict());
    }

    /** The patient reads the visit result once the doctor has concluded it, not before. */
    @Test
    void enc03_patientReadsTheResultAfterConclusion() throws Exception {
        Visit v = inExamination();
        String own = tokenFor(v.phone(), "Passw0rd!");
        call(own, get("/api/my_appointments/" + v.appointmentId() + "/result")).andExpect(status().is4xxClientError());
        call(doctor.token(), post("/api/clinical_encounters/" + v.encounterId() + "/complete")).andExpect(status().isOk());
        call(own, get("/api/my_appointments/" + v.appointmentId() + "/result")).andExpect(status().isOk())
                .andExpect(jsonPath("$.encounter.diagnosis").value("Tăng huyết áp độ 1"));
        call(tokenOf(DEMO_PATIENT), get("/api/my_appointments/" + v.appointmentId() + "/result")).andExpect(status().isNotFound());
    }

    /** BF-03: each ordered service has its own bill, at the price when ordered. */
    @Test
    void enc04_orderedServiceHasItsOwnUnpaidBill() throws Exception {
        Visit v = inExamination();
        Long order = order(v);
        call(doctor.token(), get("/api/service_orders/" + order)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ordered"));
        assertEquals(1, count("select count(*) from invoice_item ii join invoice i on i.id = ii.invoice_id where ii.service_order_id = ? "
                + "and ii.deleted_at is null and i.status = 'UNPAID' and ii.amount = 180000 and i.kind <> 'EXAM'", order));
    }

    /** BF-03: the technician starts the service only once it is paid. */
    @Test
    void enc05_serviceStartsOnlyOncePaid() throws Exception {
        Visit v = inExamination();
        Long order = order(v);
        String tech = technician();
        call(tech, post("/api/service_orders/" + order + "/start")).andExpect(status().isConflict());
        payOrder(order);
        call(tech, post("/api/service_orders/" + order + "/start")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("in_progress"));
    }

    /** The result is recorded by the technician and reviewed by the doctor; the visit is concluded only after. */
    @Test
    void enc06_resultReviewedBeforeTheVisitIsConcluded() throws Exception {
        Visit v = inExamination();
        Long order = order(v);
        payOrder(order);
        String tech = technician();
        call(tech, post("/api/service_orders/" + order + "/start")).andExpect(status().isOk());
        callJson(tech, patch("/api/service_orders/" + order + "/result"), "{\"resultText\":\"  Phổi sáng, tim không to  \"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("result_ready"))
                .andExpect(jsonPath("$.resultText").value("Phổi sáng, tim không to"));
        call(doctor.token(), post("/api/clinical_encounters/" + v.encounterId() + "/complete")).andExpect(status().isConflict());
        call(doctor.token(), post("/api/service_orders/" + order + "/review")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("reviewed"));
        call(doctor.token(), post("/api/clinical_encounters/" + v.encounterId() + "/complete")).andExpect(status().isOk());
    }

    @Test
    void enc07_cancelledOrderTakesItsBillWithIt() throws Exception {
        Visit v = inExamination();
        Long order = order(v);
        callJson(doctor.token(), post("/api/service_orders/" + order + "/cancel"), "{\"reason\":\"Đổi sang siêu âm\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("cancelled"));
        assertEquals(0, count("select count(*) from invoice_item ii join invoice i on i.id = ii.invoice_id "
                + "where ii.service_order_id = ? and ii.deleted_at is null and i.status = 'UNPAID'", order));
        call(doctor.token(), post("/api/clinical_encounters/" + v.encounterId() + "/complete")).andExpect(status().isOk());
    }

    @Test
    void enc08_technicianCannotOrder_patientCannotReadOrders() throws Exception {
        Visit v = inExamination();
        callJson(technician(), post("/api/clinical_encounters/" + v.encounterId() + "/service_orders"),
                "{\"serviceId\":%d}".formatted(XRAY)).andExpect(status().isForbidden());
        call(tokenFor(v.phone(), "Passw0rd!"), get("/api/service_orders")).andExpect(status().isForbidden());
    }

    /** A return appointment to collect results: the patient sees it, the desk records the arrival. */
    @Test
    void enc09_resultReturnIsScheduledSeenAndArrived() throws Exception {
        Visit v = inExamination();
        Long order = order(v);
        // Precondition: the patient is in the doctor's room (calling into the room is ScheduleIT's subject).
        jdbc.update("update appointment set status = 'IN_EXAM', called_at = now() where id = ?", v.appointmentId());
        // The doctor sends the patient out to have the service done; they come back for the result.
        call(doctor.token(), post("/api/clinical_encounters/" + v.encounterId() + "/send-out")).andExpect(status().isOk());
        payOrder(order);
        String tech = technician();
        call(tech, post("/api/service_orders/" + order + "/start")).andExpect(status().isOk());
        String when = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).plusDays(1).withNano(0).toString();
        Long ret = Long.valueOf(json(callJson(tech, post("/api/service_orders/" + order + "/result_return"),
                "{\"scheduledAt\":\"%s\",\"note\":\"Quay lại nhận phim\"}".formatted(when)).andExpect(status().isCreated()), "$.id"));
        call(tokenFor(v.phone(), "Passw0rd!"), get("/api/my_result_returns")).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(ret.intValue())));
        call(desk, post("/api/result_returns/" + ret + "/arrive")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARRIVED"));
        assertTrue(count("select count(*) from result_return where id = ? and arrived_at is not null", ret) == 1);
    }
}
