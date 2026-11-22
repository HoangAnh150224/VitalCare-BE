package com.vn.vitalcare.it;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** View Clinic Information, View Clinic Services and the clinic lines of Emergency Contacts: /api/public with real data. */
class PublicSiteIT extends IntegrationTestBase {

    private String insertClinic(String name, String status, String phone) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into clinic (clinic_id, clinic_name, address, contact_phone, contact_person, operating_status, "
                + "created_at, version) values (?::uuid, ?, '9 Test Street', ?, 'Internal Contact', ?, now(), 0)",
                id, name, phone, status);
        return id;
    }

    @Nested
    class Clinics {

        @Test
        void it01_openToGuestsWithHours() throws Exception {
            mvc.perform(get("/api/public/clinics"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.id == '" + CLINIC_1 + "')].name").value(hasItem("VitalCare Clinic")))
                    .andExpect(jsonPath("$[?(@.id == '" + CLINIC_1 + "')].phone").value(hasItem("+84900000000")))
                    .andExpect(jsonPath("$[?(@.id == '" + CLINIC_1 + "')].openingHours[0].dayOfWeek").exists());
        }

        @Test
        void it02_onlyOperatingClinicsListed() throws Exception {
            String closed = insertClinic("IT Closed Clinic", "INACTIVE", "+84911111111");
            String open = insertClinic("IT Open Clinic", "OPERATING", null);
            mvc.perform(get("/api/public/clinics"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[*].id", hasItem(open)))
                    .andExpect(jsonPath("$[*].id", not(hasItem(closed))));
        }

        @Test
        void it03_noInternalFields() throws Exception {
            insertClinic("IT Open Clinic", "OPERATING", "+84922222222");
            String body = mvc.perform(get("/api/public/clinics")).andReturn().getResponse().getContentAsString();
            assertEquals(false, body.contains("contactPerson") || body.contains("Internal Contact"));
        }

        @Test
        void it04_detailWithDoctorsAndDays() throws Exception {
            mvc.perform(get("/api/public/clinics/" + CLINIC_1))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("VitalCare Clinic"))
                    .andExpect(jsonPath("$.doctors[*].fullName", hasItem("BS. Nguyễn Minh Khoa")))
                    .andExpect(jsonPath("$.doctors[*].fullName", not(hasItem("ĐD. Trần Thu Hà"))));
        }

        @Test
        void it05_closedClinicIsNotFound() throws Exception {
            String closed = insertClinic("IT Closed Clinic", "INACTIVE", null);
            mvc.perform(get("/api/public/clinics/" + closed))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Clinic with id " + closed + " was not found"));
        }

        @Test
        void it06_unknownClinicIsNotFound() throws Exception {
            mvc.perform(get("/api/public/clinics/" + UUID.randomUUID())).andExpect(status().isNotFound());
        }

        @Test
        void it07_malformedIdIsRejected() throws Exception {
            int code = mvc.perform(get("/api/public/clinics/not-a-uuid")).andReturn().getResponse().getStatus();
            assertEquals(true, code == 400 || code == 404, "status " + code);
        }
    }

    @Nested
    class Services {

        @Test
        void it08_activeServicesSortedByCategoryThenName() throws Exception {
            String body = mvc.perform(get("/api/public/services"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[*].status").doesNotExist())
                    .andReturn().getResponse().getContentAsString();
            List<String> categories = JsonPath.read(body, "$[*].category");
            List<String> order = List.of("consultation", "lab", "imaging", "procedure");
            for (int i = 1; i < categories.size(); i++) {
                assertEquals(true, order.indexOf(categories.get(i - 1)) <= order.indexOf(categories.get(i)), body);
            }
            List<String> names = JsonPath.read(body, "$[*].name");
            assertEquals(true, names.contains("Khám chuyên khoa Tim mạch - Hô hấp") && names.contains("Điện tim"));
        }

        @Test
        void it09_inactiveServiceHidden() throws Exception {
            jdbc.update("insert into clinic_service (service_code, name, price, status, category, turnaround_minutes, "
                    + "created_at, version) values ('IT-OLD', 'IT Old Service', 1000, 'INACTIVE', 'LAB', 10, now(), 0)");
            mvc.perform(get("/api/public/services"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[*].name", not(hasItem("IT Old Service"))));
        }

        @Test
        void it10_priceAndTurnaroundCarried() throws Exception {
            mvc.perform(get("/api/public/services"))
                    .andExpect(jsonPath("$[?(@.name == 'Khám chuyên khoa Tim mạch - Hô hấp')].price").value(hasItem(150000.0)))
                    .andExpect(jsonPath("$[*].turnaroundMinutes", everyItem(org.hamcrest.Matchers.notNullValue())));
        }
    }
}
