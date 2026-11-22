package com.vn.vitalcare.it;

import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Clinic administration end to end: the medical service catalogue (MedicalServiceController → MedicalServiceCatalog),
 * reports and the monitoring dashboard (ReportsController → Reports, BF-08 export audited), row-level policies
 * (RowLevelPolicyController, system administrator only), clinic roles and moving a member of staff to another clinic
 * (StaffMembershipController), monitoring assignments, and the retired "view as" screens, which no role holds.
 */
class AdminIT extends CommittedIntegrationTest {

    private String clinicAdmin;
    private String desk;
    private String doctor;
    private String admin;
    private final String from = TODAY.minusDays(30).toString();
    private final String to = TODAY.toString();

    @BeforeEach
    void accounts() throws Exception {
        clinicAdmin = tokenOf(CLINIC_ADMIN_1);
        desk = tokenOf(DESK_1);
        doctor = tokenOf(DOCTOR_1);
        admin = tokenOf(ADMIN);
    }

    private static String service(String code, String name, long price) {
        return """
                {"code":"%s","name":"%s","description":"Xét nghiệm thử","price":%d,"category":"LAB","turnaroundMinutes":60,"status":"ACTIVE"}"""
                .formatted(code, name, price);
    }

    // ---------------------------------------------------------------- medical service catalogue
    @Test
    void adm01_clinicAdminAddsAndRepricesAService_othersCannotWrite() throws Exception {
        String code = "IT-SV-" + newPhone().substring(4);
        Long id = Long.valueOf(json(callJson(clinicAdmin, post("/api/medical_services"), service(code, "Xét nghiệm IT " + code, 120000))
                .andExpect(status().isCreated()), "$.id"));
        call(doctor, get("/api/medical_services/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(code)).andExpect(jsonPath("$.price").value(120000));
        callJson(clinicAdmin, patch("/api/medical_services/" + id), service(code, "Xét nghiệm IT " + code, 150000))
                .andExpect(status().isOk()).andExpect(jsonPath("$.price").value(150000));
        call(desk, get("/api/medical_services")).andExpect(status().isOk()).andExpect(jsonPath("$[*].code", hasItem(code)));
        callJson(doctor, post("/api/medical_services"), service(code + "D", "Bác sĩ thêm", 1000)).andExpect(status().isForbidden());
    }

    @Test
    void adm02_aServiceNeedsAValidPriceAndAnUnusedCode() throws Exception {
        String code = "IT-SV-" + newPhone().substring(4);
        callJson(clinicAdmin, post("/api/medical_services"), service(code, "Giá âm", -1))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.errors.price").exists());
        callJson(clinicAdmin, post("/api/medical_services"), service(code, "Lần đầu", 1000)).andExpect(status().isCreated());
        callJson(clinicAdmin, post("/api/medical_services"), service(code, "Trùng mã", 1000)).andExpect(status().is4xxClientError());
        assertEquals(1, count("select count(*) from clinic_service where service_code = ?", code));
    }

    // ---------------------------------------------------------------- reports and dashboard
    @Test
    void adm03_managerReadsEachReport_periodMustRunForwards() throws Exception {
        for (String kind : new String[] {"patients", "devices", "vitals", "alerts"}) {
            call(desk, get("/api/reports/" + kind).param("from", from).param("to", to)).andExpect(status().isOk());
        }
        call(desk, get("/api/reports/patients").param("from", to).param("to", from)).andExpect(status().is4xxClientError());
        call(doctor, get("/api/reports/patients").param("from", from).param("to", to)).andExpect(status().isForbidden());
    }

    /** BF-08: only the clinic administrator exports, and every export is in the audit log. */
    @Test
    void adm04_exportIsTheClinicAdminsAndIsAudited() throws Exception {
        int before = count("select count(*) from audit_log where entity_type = 'reports' and action = 'export'");
        call(clinicAdmin, post("/api/reports/alerts/export").param("from", from).param("to", to)).andExpect(status().isOk());
        assertEquals(before + 1, count("select count(*) from audit_log where entity_type = 'reports' and action = 'export'"));
        call(desk, post("/api/reports/alerts/export").param("from", from).param("to", to)).andExpect(status().isForbidden());
    }

    @Test
    void adm05_monitoringDashboardIsForStaffOnly() throws Exception {
        call(doctor, get("/api/monitoring_dashboard")).andExpect(status().isOk());
        call(desk, get("/api/monitoring_dashboard")).andExpect(status().isOk());
        String customer = register(newPhone(), "Khach Hang", "Passw0rd!");
        call(customer, get("/api/monitoring_dashboard")).andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- row-level policies
    @Test
    void adm06_rowLevelPoliciesAreTheSystemAdministrators() throws Exception {
        call(admin, get("/api/row_level_policies")).andExpect(status().isOk());
        call(admin, get("/api/row_level_policies/coverage")).andExpect(status().isOk());
        call(admin, get("/api/row_level_policies/metadata").param("resource", "appointments")).andExpect(status().isOk());
        Long deskUser = jdbc.queryForObject("select id from users where phone = '+84900000002'", Long.class);
        call(admin, get("/api/row_level_policies/explain").param("resource", "appointments").param("action", "read")
                .param("userId", deskUser.toString())).andExpect(status().isOk());
        call(clinicAdmin, get("/api/row_level_policies")).andExpect(status().isForbidden());
        call(desk, get("/api/row_level_policies/coverage")).andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- clinic roles and memberships
    @Test
    void adm07_clinicAdminReadsClinicRolesAndAStaffMembersRoles() throws Exception {
        String roles = call(clinicAdmin, get("/api/clinic_roles")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(roles.contains("\"code\""), roles);
        Doctor d = freshDoctor();
        call(clinicAdmin, get("/api/employees/" + d.id() + "/clinic_roles")).andExpect(status().isOk())
                .andExpect(jsonPath("$.employeeId").value(d.id()));
        call(doctor, get("/api/clinic_roles")).andExpect(status().isForbidden());
    }

    /** A move to another clinic needs a reason, is the system administrator's, and leaves the old membership on record. */
    @Test
    void adm08_systemAdminMovesADoctorToAnotherClinic() throws Exception {
        Doctor d = freshDoctor();
        callJson(admin, post("/api/employees/" + d.id() + "/memberships/transfer"),
                "{\"clinicId\":\"%s\",\"reason\":\" \"}".formatted(CLINIC_2)).andExpect(status().isUnprocessableContent());
        callJson(clinicAdmin, post("/api/employees/" + d.id() + "/memberships/transfer"),
                "{\"clinicId\":\"%s\",\"reason\":\"Điều động\"}".formatted(CLINIC_2)).andExpect(status().isForbidden());
        callJson(admin, post("/api/employees/" + d.id() + "/memberships/transfer"),
                "{\"clinicId\":\"%s\",\"reason\":\"Điều động sang cơ sở 2\"}".formatted(CLINIC_2)).andExpect(status().isOk());
        call(admin, get("/api/employees/" + d.id() + "/memberships")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[*].clinicId", hasItem(CLINIC_2)));
    }

    // ---------------------------------------------------------------- monitoring assignments, view as
    @Test
    void adm09_monitoringAssignmentsAreReadByStaff() throws Exception {
        Monitored m = monitoredPatient();
        call(doctor, get("/api/monitoring_assignments")).andExpect(status().isOk());
        call(desk, get("/api/monitoring_assignments").param("customerId", m.customerId().toString())).andExpect(status().isOk());
        call(tokenFor(m.phone(), "Passw0rd!"), get("/api/monitoring_assignments")).andExpect(status().isForbidden());
    }

    /** The "view as" screens were retired: view_as:read is granted to no role, so every account is refused. */
    @Test
    void adm10_viewAsIsGrantedToNoRole() throws Exception {
        assertEquals(0, count("select count(*) from role_permissions rp join permissions p on p.id = rp.permission_id "
                + "where p.code = 'view_as:read'"));
        for (String token : new String[] {admin, clinicAdmin, desk, doctor}) {
            call(token, get("/api/view_as/appointments")).andExpect(status().isForbidden());
        }
    }
}
