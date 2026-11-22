package com.vn.vitalcare.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

/**
 * The rest of a visit around the booking flow: rescheduling and cancelling (by the patient and by the desk), finding a
 * booking by its code, the payment rules shown before booking, a visit for another profile of the same account,
 * a doctor referring the patient to a colleague (ClinicalEncounterController /refer, /visit), and the notification
 * and clinic summary reads.
 */
class VisitExtrasIT extends CommittedIntegrationTest {

    private Long book(String token) throws Exception {
        return Long.valueOf(json(callJson(token, post("/api/my_appointments"), """
                {"clinicId":"%s","appointmentDate":"%s","reason":"Khám định kỳ","department":"Tim mạch"}""".formatted(CLINIC_1, TODAY))
                .andExpect(status().isCreated()), "$.id"));
    }

    @Test
    void vx01_patientMovesTheirBookingToAnotherDay_notIntoThePast() throws Exception {
        String own = customerWithProfile(newPhone());
        Long visit = book(own);
        String tomorrow = TODAY.plusDays(1).toString();
        callJson(own, post("/api/my_appointments/" + visit + "/reschedule"), "{\"appointmentDate\":\"%s\"}".formatted(tomorrow))
                .andExpect(status().isOk()).andExpect(jsonPath("$.appointmentDate").value(tomorrow));
        callJson(own, post("/api/my_appointments/" + visit + "/reschedule"), "{\"appointmentDate\":\"%s\"}".formatted(TODAY.minusDays(1)))
                .andExpect(status().is4xxClientError());
        callJson(own, post("/api/my_appointments/" + visit + "/reschedule"), "{}").andExpect(status().isUnprocessableContent());
    }

    @Test
    void vx02_deskReschedulesAndCancelsWithAReason() throws Exception {
        String own = customerWithProfile(newPhone());
        Long visit = book(own);
        String desk = tokenOf(DESK_1);
        String tomorrow = TODAY.plusDays(1).toString();
        callJson(desk, post("/api/appointments/" + visit + "/reschedule"), "{\"appointmentDate\":\"%s\"}".formatted(tomorrow))
                .andExpect(status().isOk()).andExpect(jsonPath("$.appointmentDate").value(tomorrow));
        callJson(desk, post("/api/appointments/" + visit + "/cancel"), "{\"reason\":\" \"}").andExpect(status().isUnprocessableContent());
        callJson(desk, post("/api/appointments/" + visit + "/cancel"), "{\"reason\":\"Khách gọi điện huỷ\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("cancelled"));
        call(own, get("/api/my_appointments/" + visit)).andExpect(jsonPath("$.status").value("cancelled"));
    }

    @Test
    void vx03_patientCancelsOnce() throws Exception {
        String own = customerWithProfile(newPhone());
        Long visit = book(own);
        call(own, post("/api/my_appointments/" + visit + "/cancel")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cancelled"));
        call(own, post("/api/my_appointments/" + visit + "/cancel")).andExpect(status().is4xxClientError());
        String other = customerWithProfile(newPhone());
        Long theirs = book(other);
        call(own, post("/api/my_appointments/" + theirs + "/cancel")).andExpect(status().isNotFound());
    }

    @Test
    void vx04_deskFindsABookingByItsCode() throws Exception {
        String own = customerWithProfile(newPhone());
        Long visit = book(own);
        String code = json(call(own, get("/api/my_appointments/" + visit)).andExpect(status().isOk()), "$.bookingCode");
        String desk = tokenOf(DESK_1);
        call(desk, get("/api/appointments/lookup").param("code", code)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(visit));
        call(desk, get("/api/appointments/lookup").param("code", "KHONG-CO-MA")).andExpect(status().isNotFound());
        call(tokenOf(DESK_2), get("/api/appointments/lookup").param("code", code)).andExpect(status().isNotFound());
    }

    @Test
    void vx05_paymentRulesAreShownBeforeBooking() throws Exception {
        String own = customerWithProfile(newPhone());
        call(own, get("/api/my_appointments/payment-rules")).andExpect(status().isOk());
        call(own, get("/api/my_appointments/payment-rules").param("clinicId", CLINIC_1)).andExpect(status().isOk());
    }

    /** UC-02 B1: the desk changes who the visit is for to another profile of the same account; a stranger cannot be picked. */
    @Test
    void vx06_visitForAnotherProfileOfTheSameAccount() throws Exception {
        String phone = newPhone();
        String own = customerWithProfile(phone);
        Long visit = book(own);
        String base = phone.substring(phone.length() - 9);
        Long child = Long.valueOf(json(callJson(own, post("/api/my_profiles"), """
                {"fullName":"Nguyễn Minh An","phone":"0912345678","dateOfBirth":"2015-03-02","gender":"male","relationship":"child",
                 "nationalId":"1%s01","nationalIdType":"cccd","province":"Hà Nội","ward":"Phường Hàng Bài","address":"1 Tràng Tiền"}"""
                .formatted(base)).andExpect(status().isCreated()), "$.id"));
        String desk = tokenOf(DESK_1);
        String stranger = newPhone();
        customerWithProfile(stranger);
        callJson(desk, post("/api/appointments/" + visit + "/examinee"), "{\"customerId\":%d}".formatted(customerId(stranger)))
                .andExpect(status().is4xxClientError());
        callJson(desk, post("/api/appointments/" + visit + "/examinee"), "{\"customerId\":%d}".formatted(child))
                .andExpect(status().isOk());
        assertEquals(child, jdbc.queryForObject("select customer_id from appointment where id = ?", Long.class, visit));
        callJson(desk, post("/api/appointments/" + visit + "/examinee"), "{}").andExpect(status().isUnprocessableContent());
    }

    /** The doctor sends the patient to a colleague of the clinic for today; the visit shows it, a reason is required. */
    @Test
    void vx07_doctorRefersThePatientToAColleague() throws Exception {
        Doctor first = freshDoctor();
        Doctor colleague = freshDoctor();
        String phone = newPhone();
        Long customer = patient(phone);
        Long visit = paidVisit(tokenFor(phone, "Passw0rd!"));
        Long enc = encounter(first, customer, visit);
        // Precondition: the patient is in the doctor's room (calling into the room is ScheduleIT's subject).
        jdbc.update("update appointment set status = 'IN_EXAM', called_at = now() where id = ?", visit);
        call(first.token(), get("/api/clinical_encounters/" + enc + "/visit")).andExpect(status().isOk())
                .andExpect(jsonPath("$.appointmentId").value(visit));
        callJson(first.token(), post("/api/clinical_encounters/" + enc + "/refer"),
                "{\"employeeId\":%d,\"reason\":\"\"}".formatted(colleague.id())).andExpect(status().isUnprocessableContent());
        callJson(first.token(), post("/api/clinical_encounters/" + enc + "/refer"),
                "{\"employeeId\":%d,\"reason\":\"Cần khám chuyên khoa\"}".formatted(colleague.id()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.appointmentId").value(visit));
        callJson(first.token(), post("/api/clinical_encounters/" + enc + "/refer"),
                "{\"employeeId\":%d,\"reason\":\"Gửi lại\"}".formatted(colleague.id())).andExpect(status().isConflict());
        callJson(first.token(), post("/api/clinical_encounters/" + enc + "/refer"),
                "{\"employeeId\":%d,\"reason\":\"Tự chuyển cho mình\"}".formatted(
                        jdbc.queryForObject("select employee_id from appointment where id = ?", Long.class, visit)))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void vx08_recentNotificationsAndTheClinicSummary() throws Exception {
        String phone = newPhone();
        patient(phone);
        String own = tokenFor(phone, "Passw0rd!");
        paidVisit(own);
        call(own, get("/api/notifications/recent")).andExpect(status().isOk());
        call(tokenOf(CLINIC_ADMIN_1), get("/api/clinics/" + CLINIC_1 + "/summary")).andExpect(status().isOk())
                .andExpect(jsonPath("$").isMap());
        call(own, get("/api/clinics/" + CLINIC_1 + "/summary")).andExpect(status().isForbidden());
    }
}
