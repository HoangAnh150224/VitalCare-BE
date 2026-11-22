package com.vn.vitalcare.it;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * FE-16 Clinic Management / First-aid Content and FE-17 Healthcare Staff end to end: the administrator's clinics and
 * their operating status as the public site shows them (ClinicController, PublicSiteController), staff created by
 * the clinic administrator with the role of their staff type and the end of a membership (EmployeeController,
 * StaffMembershipController), and the first-aid article review workflow up to the public page (FirstAidController).
 */
class ClinicStaffIT extends CommittedIntegrationTest {

    private String admin;
    private String clinicAdmin;

    @BeforeEach
    void staff() throws Exception {
        admin = tokenOf(ADMIN);
        clinicAdmin = tokenOf(CLINIC_ADMIN_1);
    }

    private String clinicBody(String name, String phone) {
        return """
                {"name":"%s","address":"12 Lê Lợi, Hà Nội","contactPhone":"%s","contactEmail":"it@vitalcare.test",
                 "contactPerson":"Nguyễn Văn Quản","operatingStatus":"OPERATING"}""".formatted(name, phone);
    }

    @Test
    void cls01_adminCreatesAClinicShownOnThePublicSite() throws Exception {
        String name = "VitalCare IT " + newPhone();
        String id = json(callJson(admin, post("/api/clinics"), clinicBody(name, "0243826123")).andExpect(status().isCreated()), "$.id");
        mvc.perform(get("/api/public/clinics")).andExpect(jsonPath("$[*].id", hasItem(id)));
        callJson(admin, post("/api/clinics"), clinicBody(name, "0243826123")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors.name").exists());
    }

    @Test
    void cls02_inactiveClinicLeavesThePublicSite() throws Exception {
        String id = json(callJson(admin, post("/api/clinics"), clinicBody("VitalCare IT " + newPhone(), "0243826124"))
                .andExpect(status().isCreated()), "$.id");
        callJson(admin, patch("/api/clinics/" + id + "/operating-status"), "{\"operatingStatus\":\"INACTIVE\"}").andExpect(status().isOk());
        mvc.perform(get("/api/public/clinics")).andExpect(jsonPath("$[*].id", not(hasItem(id))));
        mvc.perform(get("/api/public/clinics/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void cls03_invalidPhoneAndNonAdminRefused() throws Exception {
        callJson(admin, post("/api/clinics"), clinicBody("VitalCare IT " + newPhone(), "12345")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors.contactPhone").exists());
        callJson(clinicAdmin, post("/api/clinics"), clinicBody("VitalCare IT " + newPhone(), "0243826125")).andExpect(status().isForbidden());
    }

    private org.springframework.test.web.servlet.ResultActions createStaff(String token, String phone, String type, String clinic)
            throws Exception {
        return callJson(token, post("/api/employees"), """
                {"phone":"%s","fullName":"Nhân Viên Mới","password":"Passw0rd!","staffType":"%s","specialty":"Tim mạch","clinicId":"%s"}"""
                .formatted(phone, type, clinic));
    }

    /** A doctor created by the clinic administrator signs in with the DOCTOR role and works at that clinic. */
    @Test
    void cls04_clinicAdminCreatesADoctorWhoSignsInAsDoctor() throws Exception {
        String phone = newPhone();
        createStaff(clinicAdmin, phone, "doctor", CLINIC_1).andExpect(status().isCreated())
                .andExpect(jsonPath("$.clinicId").value(CLINIC_1));
        call(tokenFor(phone, "Passw0rd!"), get("/api/auth/permissions")).andExpect(jsonPath("$.roles", hasItem("DOCTOR")));
        createStaff(clinicAdmin, phone, "doctor", CLINIC_1).andExpect(status().is4xxClientError());
        assertEquals(1, count("select count(*) from employee e join users u on u.id = e.user_id where u.phone = ?", intl(phone)));
    }

    @Test
    void cls05_receptionistGetsTheFrontDeskRole() throws Exception {
        String phone = newPhone();
        createStaff(clinicAdmin, phone, "receptionist", CLINIC_1).andExpect(status().isCreated());
        call(tokenFor(phone, "Passw0rd!"), get("/api/auth/permissions")).andExpect(jsonPath("$.roles", hasItem("MANAGER")));
    }

    /** A clinic administrator adds staff to their own clinic only. */
    @Test
    void cls06_clinicAdminCannotStaffAnotherClinic() throws Exception {
        String phone = newPhone();
        createStaff(clinicAdmin, phone, "doctor", CLINIC_2).andExpect(status().is4xxClientError());
        assertEquals(0, count("select count(*) from users where phone = ?", intl(phone)));
    }

    /** Ending a membership needs a reason; the account then cannot sign in and the history keeps it. */
    @Test
    void cls07_endedMembershipCannotSignIn() throws Exception {
        String phone = newPhone();
        Long id = Long.valueOf(json(createStaff(clinicAdmin, phone, "doctor", CLINIC_1).andExpect(status().isCreated()), "$.id"));
        callJson(clinicAdmin, post("/api/employees/" + id + "/memberships/end"), "{\"reason\":\" \"}").andExpect(status().isUnprocessableContent());
        callJson(clinicAdmin, post("/api/employees/" + id + "/memberships/end"), "{\"reason\":\"Nghỉ việc theo nguyện vọng\"}")
                .andExpect(status().isOk());
        postJson("/api/auth/login", "{\"phone\":\"%s\",\"password\":\"Passw0rd!\"}".formatted(phone)).andExpect(status().isUnauthorized());
        call(clinicAdmin, get("/api/employees/" + id + "/memberships")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
    }

    // ---------------------------------------------------------------- first-aid articles
    private Long draft(String token, String title) throws Exception {
        return Long.valueOf(json(callJson(token, post("/api/first_aid_articles"), """
                {"title":"%s","category":"wound","summary":"Làm mát vết bỏng bằng nước sạch","body":"## Làm ngay\\n1. Xả nước mát 20 phút",
                 "emergencyWarning":"Gọi 115 nếu bỏng rộng"}""".formatted(title)).andExpect(status().isCreated()), "$.id"));
    }

    /** Draft → submitted → approved by someone else → published: only then on the public page. */
    @Test
    void cls08_articleIsPublicOnlyAfterSomeoneElseApproves() throws Exception {
        Doctor author = freshDoctor();
        Doctor reviewer = freshDoctor();
        // A title of its own, archived at the end: the public list is shared with FirstAidIT, which searches the seed.
        String title = "Sơ cứu IT công khai " + newPhone();
        Long id = draft(author.token(), title);
        mvc.perform(get("/api/public/first_aid").param("q", title)).andExpect(jsonPath("$[*].title", not(hasItem(title))));
        call(author.token(), post("/api/first_aid_articles/" + id + "/submit")).andExpect(status().isOk());
        call(author.token(), post("/api/first_aid_articles/" + id + "/approve")).andExpect(status().is4xxClientError());
        call(reviewer.token(), post("/api/first_aid_articles/" + id + "/approve")).andExpect(status().isOk());
        call(reviewer.token(), post("/api/first_aid_articles/" + id + "/publish")).andExpect(status().isOk());
        mvc.perform(get("/api/public/first_aid").param("q", title)).andExpect(jsonPath("$[*].title", hasItem(title)));
        callJson(reviewer.token(), post("/api/first_aid_articles/" + id + "/archive"), "{\"note\":\"Dọn dữ liệu test\"}").andExpect(status().isOk());
    }

    @Test
    void cls09_returnNeedsAReason_archivedLeavesThePublicPage() throws Exception {
        Doctor author = freshDoctor();
        Doctor reviewer = freshDoctor();
        Long id = draft(author.token(), "Sơ cứu IT " + newPhone());
        call(author.token(), post("/api/first_aid_articles/" + id + "/submit")).andExpect(status().isOk());
        callJson(reviewer.token(), post("/api/first_aid_articles/" + id + "/reject"), "{\"note\":\"\"}").andExpect(status().is4xxClientError());
        callJson(reviewer.token(), post("/api/first_aid_articles/" + id + "/reject"), "{\"note\":\"Bổ sung hình minh hoạ\"}")
                .andExpect(status().isOk());
        String title = "Sơ cứu IT2 " + newPhone();
        Long other = draft(author.token(), title);
        call(author.token(), post("/api/first_aid_articles/" + other + "/submit")).andExpect(status().isOk());
        call(reviewer.token(), post("/api/first_aid_articles/" + other + "/approve")).andExpect(status().isOk());
        call(reviewer.token(), post("/api/first_aid_articles/" + other + "/publish")).andExpect(status().isOk());
        callJson(reviewer.token(), post("/api/first_aid_articles/" + other + "/archive"), "{\"note\":\"Nội dung cũ\"}").andExpect(status().isOk());
        mvc.perform(get("/api/public/first_aid").param("q", title)).andExpect(jsonPath("$[*].title", not(hasItem(title))));
    }
}
