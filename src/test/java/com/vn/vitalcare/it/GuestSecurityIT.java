package com.vn.vitalcare.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

/** What a guest may and may not reach, through the real security filter chain. */
class GuestSecurityIT extends IntegrationTestBase {

    @Test
    void sec01_publicPagesNeedNoToken() throws Exception {
        for (String url : new String[] {"/api/public/clinics", "/api/public/services", "/api/public/first_aid"}) {
            mvc.perform(get(url)).andExpect(status().isOk());
        }
    }

    @Test
    void sec02_protectedEndpointWithoutTokenIs401() throws Exception {
        mvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentication is required to access this resource"));
    }

    @Test
    void sec03_garbageTokenIs401() throws Exception {
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer not.a.jwt")).andExpect(status().isUnauthorized());
    }

    @Test
    void sec04_customerCannotOpenStaffInbox() throws Exception {
        String token = register(newPhone(), "Nguyen Van A", "Passw0rd!");
        mvc.perform(get("/api/conversations").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void sec05_selfRegistrationCannotPickARole() throws Exception {
        String phone = newPhone();
        String code = registrationCode(phone);
        postJson("/api/auth/register", """
                {"phone":"%s","otp":"%s","fullName":"Nguyen Van A","password":"Passw0rd!","role":"ADMIN","roleCode":"ADMIN"}"""
                .formatted(phone, code))
                .andExpect(status().isOk());
        assertEquals(0, count("select count(*) from user_roles ur join roles r on r.id = ur.role_id join users u "
                + "on u.id = ur.user_id where u.phone = ? and r.code <> 'GUEST'", intl(phone)));
    }

    /** Sign-in names the field that is wrong (agreed requirement), always with 401 and no token. */
    @Test
    void sec06_loginSaysWhichFieldIsWrongButAlways401() throws Exception {
        String phone = newPhone();
        register(phone, "Nguyen Van A", "Passw0rd!");
        postJson("/api/auth/login", """
                {"phone":"%s","password":"Wrong123!"}""".formatted(phone))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Incorrect password"))
                .andExpect(jsonPath("$.accessToken").doesNotExist());
        postJson("/api/auth/login", """
                {"phone":"%s","password":"Wrong123!"}""".formatted(newPhone()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("No account uses this phone number"))
                .andExpect(jsonPath("$.accessToken").doesNotExist());
    }

    @Test
    void sec07_passwordHashNeverReturned() throws Exception {
        String phone = newPhone();
        String body = registerWith(phone, "Nguyen Van A", "Passw0rd!")
                .andReturn().getResponse().getContentAsString();
        assertEquals(false, body.contains("password") || body.contains("$2a$") || body.contains("$2b$"), body);
    }
}
