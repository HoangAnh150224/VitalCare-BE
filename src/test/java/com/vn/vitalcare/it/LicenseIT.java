package com.vn.vitalcare.it;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/**
 * Practising licences and certificates: a doctor keeps their own (ResultsController /api/my_licenses → Licenses →
 * professional_license, scan in file storage), and the clinic administrator sees the clinic's, with the ones about
 * to expire picked out.
 */
class LicenseIT extends CommittedIntegrationTest {

    private static final byte[] PDF = "%PDF-1.4\n1 0 obj<<>>endobj\ntrailer<<>>\n%%EOF".getBytes();

    private static String licence(String kind, String number, java.time.LocalDate expires) {
        return """
                {"kind":"%s","title":"Chứng chỉ hành nghề khám bệnh, chữa bệnh","licenseNumber":"%s","issuer":"Sở Y tế Hà Nội",
                 "issuedOn":"2020-01-15","expiresOn":%s}""".formatted(kind, number, expires == null ? "null" : "\"" + expires + "\"");
    }

    private Long add(Doctor d, String number, java.time.LocalDate expires) throws Exception {
        return Long.valueOf(json(callJson(d.token(), post("/api/my_licenses"), licence("license", number, expires))
                .andExpect(status().is2xxSuccessful()), "$.id"));
    }

    @Test
    void lic01_doctorAddsALicence_clinicAdminSeesIt() throws Exception {
        Doctor d = freshDoctor();
        String number = "IT-" + newPhone().substring(3);
        Long id = add(d, number, TODAY.plusYears(3));
        call(d.token(), get("/api/my_licenses")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id)).andExpect(jsonPath("$[0].kind").value("LICENSE"))
                .andExpect(jsonPath("$[0].hasFile").value(false));
        call(tokenOf(CLINIC_ADMIN_1), get("/api/licenses")).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].licenseNumber", hasItem(number)));
    }

    @Test
    void lic02_kindAndNumberAreChecked() throws Exception {
        Doctor d = freshDoctor();
        callJson(d.token(), post("/api/my_licenses"), licence("diploma", "IT-X1", null))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.errors.kind").exists());
        callJson(d.token(), post("/api/my_licenses"), licence("certificate", "", null))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.errors.licenseNumber").exists());
        assertEquals(0, count("select count(*) from professional_license where employee_id = ?", d.id()));
    }

    /** The scan is checked by its content: a PDF is kept and read back by the doctor and the clinic admin; text is refused. */
    @Test
    void lic03_scanIsCheckedByContentAndReadBack() throws Exception {
        Doctor d = freshDoctor();
        Long id = add(d, "IT-" + newPhone().substring(3), null);
        mvc.perform(multipart("/api/my_licenses/" + id + "/file")
                        .file(new MockMultipartFile("file", "giay-phep.pdf", "application/pdf", "chỉ là chữ".getBytes()))
                        .header("Authorization", bearer(d.token())))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(multipart("/api/my_licenses/" + id + "/file")
                        .file(new MockMultipartFile("file", "giay-phep.pdf", "application/pdf", PDF))
                        .header("Authorization", bearer(d.token())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hasFile").value(true));
        call(d.token(), get("/api/my_licenses/" + id + "/file")).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("application/pdf")));
        call(tokenOf(CLINIC_ADMIN_1), get("/api/licenses/" + id + "/file")).andExpect(status().isOk());
    }

    @Test
    void lic04_onlyTheHolderChangesOrRemovesIt() throws Exception {
        Doctor holder = freshDoctor();
        Doctor other = freshDoctor();
        String number = "IT-" + newPhone().substring(3);
        Long id = add(holder, number, null);
        callJson(other.token(), put("/api/my_licenses/" + id), licence("license", "IT-HIJACK", null)).andExpect(status().isNotFound());
        call(other.token(), delete("/api/my_licenses/" + id)).andExpect(status().isNotFound());
        call(holder.token(), delete("/api/my_licenses/" + id)).andExpect(status().isNoContent());
        call(holder.token(), get("/api/my_licenses")).andExpect(jsonPath("$[*].id", not(hasItem(id.intValue()))));
    }

    @Test
    void lic05_expiringFilterAndWhoMayLook() throws Exception {
        Doctor d = freshDoctor();
        String soon = "IT-" + newPhone().substring(3);
        String later = "IT-" + newPhone().substring(3);
        add(d, soon, TODAY.plusDays(10));
        add(d, later, TODAY.plusYears(5));
        String admin = tokenOf(CLINIC_ADMIN_1);
        call(admin, get("/api/licenses").param("expiring", "true")).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].licenseNumber", hasItem(soon)))
                .andExpect(jsonPath("$[*].licenseNumber", not(hasItem(later))));
        call(d.token(), get("/api/licenses")).andExpect(status().isForbidden());
        call(tokenOf(DESK_1), get("/api/my_licenses")).andExpect(status().isForbidden());
    }
}
