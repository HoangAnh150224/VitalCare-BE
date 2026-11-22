package com.vn.vitalcare.it;

import static org.hamcrest.Matchers.contains;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vn.vitalcare.identity.user.entity.User;
import com.vn.vitalcare.share.security.JwtService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

/** Log In for every seeded role: authentication, the role the database grants, 403 outside it, and session end. */
class LoginRoleIT extends IntegrationTestBase {

    @Autowired
    private JwtService jwtService;

    private ResultActions signIn(String phone, String password) throws Exception {
        return postJson("/api/auth/login", """
                {"phone":"%s","password":"%s"}""".formatted(phone, password));
    }

    private String token(String phone, String password) throws Exception {
        return json(signIn(phone, password).andExpect(status().isOk()), "$.accessToken");
    }

    /** IT-LOG-01: each seeded account signs in and /permissions answers exactly its role from user_roles. */
    @ParameterizedTest(name = "{0} -> {2}")
    @CsvSource({
            "0900000001, Admin@123,   ADMIN",
            "0900000002, Manager@123, MANAGER",
            "0900000003, User@123,    GUEST",
            "0900000011, Staff@123,   DOCTOR",
            "0900000012, Staff@123,   MANAGER",
            "0900000031, VitalCareTest#2026, CLINIC_ADMIN",
            "0900000041, VitalCareTest#2026, GUEST",
            "0900000501, Staff@123,   PATIENT"})
    void log01_everyRoleSignsInWithItsOwnRole(String phone, String password, String role) throws Exception {
        String token = token(phone, password);
        mvc.perform(get("/api/auth/permissions").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", contains(role)));
        assertEquals(role, jdbc.queryForObject("select r.code from users u join user_roles ur on ur.user_id = u.id "
                + "join roles r on r.id = ur.role_id where u.phone = ?", String.class, intl(phone)));
    }

    /** IT-LOG-02: the access token names the account (sub = users.id) and carries no role. */
    @Test
    void log02_tokenIdentifiesTheAccountButHoldsNoRole() throws Exception {
        String token = token("0900000011", "Staff@123");
        String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);
        Long id = jdbc.queryForObject("select id from users where phone = '+84900000011'", Long.class);
        assertEquals(true, payload.contains("\"sub\":\"" + id + "\""));
        assertFalse(payload.contains("DOCTOR"));
        assertFalse(payload.contains("roles"));
    }

    /** IT-LOG-03: users:read is the System Administrator's; others get 403, no token gets 401. */
    @Test
    void log03_onlyAdminListsUsers() throws Exception {
        mvc.perform(get("/api/users").header("Authorization", "Bearer " + token("0900000001", "Admin@123")))
                .andExpect(status().isOk());
        for (String[] who : new String[][] {
                {"0900000002", "Manager@123"}, {"0900000011", "Staff@123"},
                {"0900000012", "Staff@123"}, {"0900000501", "Staff@123"}}) {
            mvc.perform(get("/api/users").header("Authorization", "Bearer " + token(who[0], who[1])))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/users")).andExpect(status().isUnauthorized());
    }

    /** IT-LOG-04: a signed token past its expiry is refused. */
    @Test
    void log04_expiredTokenIs401() throws Exception {
        Long id = jdbc.queryForObject("select id from users where phone = '+84900000011'", Long.class);
        User doctor = em.find(User.class, id);
        String expired = jwtService.issueAccessToken(doctor, Instant.now().minusSeconds(24 * 3600));
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized());
        String fresh = jwtService.issueAccessToken(doctor, Instant.now());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + fresh))
                .andExpect(status().isOk());
    }

    /** IT-LOG-05: an account disabled while signed in cannot renew its session. */
    @Test
    void log05_disabledMidSessionCannotRefresh() throws Exception {
        String phone = newPhone();
        String refresh = json(registerWith(phone, "Nguyen Van A", "Passw0rd!"), "$.refreshToken");
        sync();
        jdbc.update("update users set status = 'INACTIVE' where phone = ?", intl(phone));
        postJson("/api/auth/refresh", """
                {"refreshToken":"%s"}""".formatted(refresh))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("This account is no longer active"));
    }

    /** IT-LOG-06: a successful sign-in records the time in users.last_login_at and opens one refresh_tokens row. */
    @Test
    void log06_signInIsRecorded() throws Exception {
        Long id = jdbc.queryForObject("select id from users where phone = '+84900000012'", Long.class);
        Integer before = count("select count(*) from refresh_tokens where user_id = ? and revoked_at is null", id);
        signIn("0900000012", "Staff@123").andExpect(status().isOk());
        sync();
        assertEquals(before + 1, count("select count(*) from refresh_tokens where user_id = ? and revoked_at is null", id));
        assertEquals(1, count("select count(*) from users where id = ? and last_login_at > now() - interval '1 minute'", id));
    }
}
