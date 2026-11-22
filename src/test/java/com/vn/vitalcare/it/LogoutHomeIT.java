package com.vn.vitalcare.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/** User Logout (POST /api/auth/logout) and the Home Page's ways in, through the real filter chain and database. */
class LogoutHomeIT extends IntegrationTestBase {

    private ResultActions login(String phone, String password) throws Exception {
        return postJson("/api/auth/login", """
                {"phone":"%s","password":"%s"}""".formatted(phone, password)).andExpect(status().isOk());
    }

    private ResultActions logout(String refresh) throws Exception {
        return postJson("/api/auth/logout", """
                {"refreshToken":"%s"}""".formatted(refresh));
    }

    /** IT-OUT-01: logout answers success and marks exactly that refresh_tokens row revoked. */
    @Test
    void out01_logoutRevokesThatSessionInTheDatabase() throws Exception {
        String refresh = json(login("0900000012", "Staff@123"), "$.refreshToken");
        Long id = jdbc.queryForObject("select id from users where phone = '+84900000012'", Long.class);
        sync();
        Integer openBefore = count("select count(*) from refresh_tokens where user_id = ? and revoked_at is null", id);
        logout(refresh).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        sync();
        assertEquals(openBefore - 1, count("select count(*) from refresh_tokens where user_id = ? and revoked_at is null", id));
        postJson("/api/auth/refresh", """
                {"refreshToken":"%s"}""".formatted(refresh)).andExpect(status().isUnauthorized());
    }

    /** IT-OUT-02: signing out on one device leaves the account's other session working. */
    @Test
    void out02_otherSessionsStayOpen() throws Exception {
        String deviceA = json(login("0900000011", "Staff@123"), "$.refreshToken");
        String deviceB = json(login("0900000011", "Staff@123"), "$.refreshToken");
        logout(deviceA).andExpect(status().isOk());
        postJson("/api/auth/refresh", """
                {"refreshToken":"%s"}""".formatted(deviceA)).andExpect(status().isUnauthorized());
        postJson("/api/auth/refresh", """
                {"refreshToken":"%s"}""".formatted(deviceB)).andExpect(status().isOk());
    }

    /** IT-OUT-03: logout never fails — no body, an unknown token, the same token twice. */
    @Test
    void out03_logoutNeverFails() throws Exception {
        mvc.perform(post("/api/auth/logout")).andExpect(status().isOk());
        logout("not-a-real-token").andExpect(status().isOk());
        String refresh = json(login("0900000012", "Staff@123"), "$.refreshToken");
        logout(refresh).andExpect(status().isOk());
        logout(refresh).andExpect(status().isOk());
    }

    /** IT-OUT-04: the access token in hand is not revoked by logout; it simply lapses at its expiry (stateless JWT, 15 minutes). */
    @Test
    void out04_accessTokenLivesUntilItsExpiry() throws Exception {
        ResultActions signedIn = login("0900000012", "Staff@123");
        String access = json(signedIn, "$.accessToken");
        assertEquals("900", json(signedIn, "$.expiresIn"));
        logout(json(signedIn, "$.refreshToken")).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + access)).andExpect(status().isOk());
    }

    /** IT-HOME-01: every page the Home Page links to for a guest answers without signing in. */
    @Test
    void home01_publicLinksAnswerWithoutSignIn() throws Exception {
        mvc.perform(get("/api/public/clinics")).andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
        mvc.perform(get("/api/public/services")).andExpect(status().isOk());
        mvc.perform(get("/api/public/first_aid")).andExpect(status().isOk());
    }

    /** IT-HOME-02: "Vào trang của tôi" leads to signed-in data: refused without a token, served with one. */
    @Test
    void home02_myPageNeedsASession() throws Exception {
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        String access = json(login("0900000501", "Staff@123"), "$.accessToken");
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value("+84900000501"));
    }
}
