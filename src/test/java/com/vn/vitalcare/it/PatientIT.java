package com.vn.vitalcare.it;

import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * FE-07 Patient Management end to end: the desk's customer list, details, update, activation and activity status
 * (CustomerController); the account owner's own and family profiles (MyProfileController, BR family limit and
 * unique ID number); and feedback on a concluded visit with the clinic's reply and removal (ResultsController).
 */
class PatientIT extends CommittedIntegrationTest {

    private String desk;

    @BeforeEach
    void staff() throws Exception {
        desk = tokenOf(DESK_1);
    }

    @Test
    void pat01_deskFindsTheCustomerAndOpensTheirDetails() throws Exception {
        String phone = newPhone();
        customerWithProfile(phone);
        Long id = customerId(phone);
        call(desk, get("/api/customers").param("q", phone)).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(id.intValue())));
        call(desk, get("/api/customers/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value(intl(phone)))
                .andExpect(jsonPath("$.status").value("neutral"));
    }

    /** Patient activation turns a customer into a patient (the PATIENT role on the next request), once. */
    @Test
    void pat02_activationMakesAPatientOnce() throws Exception {
        String phone = newPhone();
        String own = customerWithProfile(phone);
        Long id = customerId(phone);
        call(desk, post("/api/customers/" + id + "/activate")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("patient"));
        call(own, get("/api/auth/permissions")).andExpect(jsonPath("$.roles", hasItem("PATIENT")));
        assertEquals(1, count("select count(*) from customer where id = ? and patient_activated_at is not null "
                + "and patient_activation_source is not null", id));
        call(desk, post("/api/customers/" + id + "/activate")).andExpect(status().isConflict());
    }

    @Test
    void pat03_deskUpdatesPatientInformation_invalidDateRefused() throws Exception {
        Long id = patient(newPhone());
        callJson(desk, patch("/api/customers/" + id), """
                {"dateOfBirth":"1985-07-20","gender":"male","address":"25 Lý Thường Kiệt",
                 "emergencyContactName":"Nguyễn Thị Hoa","emergencyContactPhone":"0912345678"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emergencyContactName").value("Nguyễn Thị Hoa"));
        assertEquals("25 Lý Thường Kiệt", jdbc.queryForObject("select address from customer where id = ?", String.class, id));
        callJson(desk, patch("/api/customers/" + id), "{\"dateOfBirth\":\"2999-01-01\"}").andExpect(status().isUnprocessableContent());
    }

    @Test
    void pat04_activityStatusIsRecordedWithReasonAndWho() throws Exception {
        Long id = patient(newPhone());
        callJson(desk, post("/api/customers/" + id + "/activity-status"), """
                {"status":"paused","reason":"Đi công tác 2 tháng"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activityStatus").value("paused"))
                .andExpect(jsonPath("$.activityReason").value("Đi công tác 2 tháng"));
        assertEquals(1, count("select count(*) from customer where id = ? and activity_changed_by_id is not null", id));
        callJson(desk, post("/api/customers/" + id + "/activity-status"), "{\"status\":\"gone\"}")
                .andExpect(status().isUnprocessableContent());
    }

    private String relative(String name, String nationalId) {
        return """
                {"fullName":"%s","phone":"0912345678","dateOfBirth":"2015-03-02","gender":"male","relationship":"child","nationalId":"%s",
                 "nationalIdType":"cccd","province":"Hà Nội","ward":"Phường Hàng Bài","address":"1 Tràng Tiền"}"""
                .formatted(name, nationalId);
    }

    /** An account keeps at most two family members (BR family limit). */
    @Test
    void pat05_ownerAddsFamilyMembersUpToTwo() throws Exception {
        String phone = newPhone();
        String own = customerWithProfile(phone);
        String base = phone.substring(1);
        callJson(own, post("/api/my_profiles"), relative("Nguyễn Minh An", "1" + base + "01")).andExpect(status().isCreated());
        callJson(own, post("/api/my_profiles"), relative("Nguyễn Minh Bình", "1" + base + "02")).andExpect(status().isCreated());
        call(own, get("/api/my_profiles")).andExpect(jsonPath("$.length()").value(3));
        callJson(own, post("/api/my_profiles"), relative("Nguyễn Minh Châu", "1" + base + "03")).andExpect(status().is4xxClientError());
        assertEquals(2, count("select count(*) from customer where guardian_id = ?", customerId(phone)));
    }

    @Test
    void pat06_nationalIdUsedByAnotherProfileIsRefused() throws Exception {
        String first = newPhone();
        customerWithProfile(first);
        String taken = jdbc.queryForObject("select national_id from customer where id = ?", String.class, customerId(first));
        String own = customerWithProfile(newPhone());
        callJson(own, post("/api/my_profiles"), relative("Trần Văn Dũng", taken))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors.nationalId").exists());
    }

    /** Feedback is given once per concluded visit, by its patient. */
    @Test
    void pat07_feedbackOnAConcludedVisitOnce() throws Exception {
        Doctor doctor = freshDoctor();
        String phone = newPhone();
        patient(phone);
        String own = tokenFor(phone, "Passw0rd!");
        Long visit = completedVisit(phone, doctor);
        callJson(own, post("/api/my_feedback"), """
                {"appointmentId":%d,"rating":5,"comment":"Bác sĩ tận tình"}""".formatted(visit))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.rating").value(5));
        callJson(own, post("/api/my_feedback"), """
                {"appointmentId":%d,"rating":4,"comment":"Lần hai"}""".formatted(visit)).andExpect(status().isConflict());
        callJson(own, post("/api/my_feedback"), """
                {"appointmentId":%d,"rating":9}""".formatted(visit)).andExpect(status().is4xxClientError());
        assertEquals(1, count("select count(*) from patient_feedback where appointment_id = ?", visit));
    }

    @Test
    void pat08_clinicRepliesAndRemovesFeedback() throws Exception {
        Doctor doctor = freshDoctor();
        String phone = newPhone();
        patient(phone);
        String own = tokenFor(phone, "Passw0rd!");
        Long visit = completedVisit(phone, doctor);
        Long feedback = Long.valueOf(json(callJson(own, post("/api/my_feedback"), """
                {"appointmentId":%d,"rating":2,"comment":"Chờ lâu"}""".formatted(visit)).andExpect(status().isCreated()), "$.id"));
        callJson(desk, put("/api/feedback/" + feedback + "/reply"), "{\"reply\":\"  Xin lỗi anh/chị, chúng tôi sẽ cải thiện  \"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.reply").value("Xin lỗi anh/chị, chúng tôi sẽ cải thiện"));
        call(own, get("/api/my_feedback")).andExpect(jsonPath("$[0].reply").value("Xin lỗi anh/chị, chúng tôi sẽ cải thiện"));
        // Removing feedback is the clinic administrator's (feedback:delete), with a reason.
        callJson(tokenOf(CLINIC_ADMIN_1), delete("/api/feedback/" + feedback), "{\"reason\":\"Spam\"}").andExpect(status().is2xxSuccessful());
        callJson(desk, put("/api/feedback/" + feedback + "/reply"), "{\"reply\":\"Thêm\"}").andExpect(status().isConflict());
        assertEquals(1, count("select count(*) from patient_feedback where id = ? and deleted_at is not null", feedback));
    }

    @Test
    void pat09_customerCannotUseTheDeskScreens() throws Exception {
        String own = customerWithProfile(newPhone());
        call(own, get("/api/customers")).andExpect(status().isForbidden());
        call(own, get("/api/feedback")).andExpect(status().isForbidden());
    }
}
