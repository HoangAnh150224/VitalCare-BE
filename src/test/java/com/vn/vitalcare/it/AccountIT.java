package com.vn.vitalcare.it;

import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/** Register Account and Log In: controller, validation, services, password hashing, JWT and the tables behind them. */
class AccountIT extends IntegrationTestBase {

    @Nested
    class Register {

        @Test
        void it01_createsAccountCustomerAndSession() throws Exception {
            String phone = newPhone();
            ResultActions r = registerWith(phone, "  Nguyen Van A ", "Passw0rd!")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.tokenType").value("Bearer"))
                    .andExpect(jsonPath("$.user.phone").value(intl(phone)))
                    .andExpect(jsonPath("$.user.fullName").value("Nguyen Van A"));
            assertNotNull(json(r, "$.accessToken"));
            assertNotNull(json(r, "$.refreshToken"));

            sync();
            Map<String, Object> user = jdbc.queryForMap("select id, password_hash, status from users where phone = ?", intl(phone));
            assertEquals("ACTIVE", user.get("status"));
            assertTrue(user.get("password_hash").toString().startsWith("$2"));
            assertNotEquals("Passw0rd!", user.get("password_hash"));
            assertEquals(1, count("select count(*) from user_roles ur join roles ro on ro.id = ur.role_id "
                    + "where ur.user_id = ? and ro.code = 'GUEST'", user.get("id")));
            assertEquals(1, count("select count(*) from customer where user_id = ?", user.get("id")));
            assertEquals(1, count("select count(*) from refresh_tokens where user_id = ?", user.get("id")));
        }

        @Test
        void it02_phoneTakenInAnySpelling() throws Exception {
            String phone = newPhone();
            register(phone, "Nguyen Van A", "Passw0rd!");
            postJson("/api/auth/register/otp", """
                    {"phone":"%s"}""".formatted(intl(phone)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("Số điện thoại đã được sử dụng. Xin vui lòng nhập một số khác."));
            assertEquals(1, count("select count(*) from users where phone = ?", intl(phone)));
        }

        @Test
        void it03_invalidFieldsAre422AndNothingIsWritten() throws Exception {
            int before = count("select count(*) from users");
            postJson("/api/auth/register", """
                    {"phone":"12345","otp":"","fullName":"","password":"short"}""")
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.message").value("Validation failed"))
                    .andExpect(jsonPath("$.errors.otp").exists())
                    .andExpect(jsonPath("$.errors.fullName").exists())
                    .andExpect(jsonPath("$.errors.password").value("Password must be between 8 and 72 characters"));
            assertEquals(before, count("select count(*) from users"));
        }

        @Test
        void it04_passwordOver72BytesRefused() throws Exception {
            String phone = newPhone();
            String code = registrationCode(phone);
            postJson("/api/auth/register", """
                    {"phone":"%s","otp":"%s","fullName":"Nguyen Van A","password":"%s"}""".formatted(phone, code, "ệ".repeat(30)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors.password").exists());
            assertEquals(0, count("select count(*) from users where phone = ?", intl(phone)));
        }

        @Test
        void it05_tokenOpensMeAndPermissions() throws Exception {
            String phone = newPhone();
            String token = register(phone, "Nguyen Van A", "Passw0rd!");
            mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.phone").value(intl(phone)))
                    .andExpect(jsonPath("$.fullName").value("Nguyen Van A"));
            mvc.perform(get("/api/auth/permissions").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.roles", hasItem("GUEST")));
        }
    }

    @Nested
    class Login {

        @Test
        void it06_bothSpellingsSignIn() throws Exception {
            String phone = newPhone();
            register(phone, "Nguyen Van A", "Passw0rd!");
            jdbc.update("update users set last_login_at = null where phone = ?", intl(phone));
            postJson("/api/auth/login", """
                    {"phone":"%s","password":"Passw0rd!"}""".formatted(phone))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken").exists())
                    .andExpect(jsonPath("$.user.phone").value(intl(phone)));
            postJson("/api/auth/login", """
                    {"phone":"%s","password":"Passw0rd!"}""".formatted(intl(phone)))
                    .andExpect(status().isOk());
            assertNotNull(jdbc.queryForObject("select last_login_at from users where phone = ?", Object.class, intl(phone)));
        }

        @Test
        void it07_wrongPassword401() throws Exception {
            String phone = newPhone();
            register(phone, "Nguyen Van A", "Passw0rd!");
            postJson("/api/auth/login", """
                    {"phone":"%s","password":"Wrong123!"}""".formatted(phone))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value("Incorrect password"));
        }

        @Test
        void it08_unknownPhoneSameMessage() throws Exception {
            postJson("/api/auth/login", """
                    {"phone":"%s","password":"Passw0rd!"}""".formatted(newPhone()))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value("No account uses this phone number"));
        }

        @Test
        void it09_inactiveAccountRefused() throws Exception {
            String phone = newPhone();
            register(phone, "Nguyen Van A", "Passw0rd!");
            sync();
            jdbc.update("update users set status = 'INACTIVE' where phone = ?", intl(phone));
            postJson("/api/auth/login", """
                    {"phone":"%s","password":"Passw0rd!"}""".formatted(phone))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value("This account is no longer active"));
        }

        @Test
        void it10_blankFields422() throws Exception {
            postJson("/api/auth/login", """
                    {"phone":"","password":""}""")
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors.phone").value("Phone number is required"))
                    .andExpect(jsonPath("$.errors.password").value("Password is required"));
        }

        @Test
        void it11_refreshRotatesAndBurnsTheOldToken() throws Exception {
            String phone = newPhone();
            String refresh = json(registerWith(phone, "Nguyen Van A", "Passw0rd!"), "$.refreshToken");
            ResultActions next = postJson("/api/auth/refresh", """
                    {"refreshToken":"%s"}""".formatted(refresh)).andExpect(status().isOk());
            assertNotEquals(refresh, json(next, "$.refreshToken"));
            postJson("/api/auth/refresh", """
                    {"refreshToken":"%s"}""".formatted(refresh)).andExpect(status().isUnauthorized());
        }

        @Test
        void it12_logoutEndsTheSession() throws Exception {
            String phone = newPhone();
            String refresh = json(registerWith(phone, "Nguyen Van A", "Passw0rd!"), "$.refreshToken");
            postJson("/api/auth/logout", """
                    {"refreshToken":"%s"}""".formatted(refresh)).andExpect(status().is2xxSuccessful());
            postJson("/api/auth/refresh", """
                    {"refreshToken":"%s"}""".formatted(refresh)).andExpect(status().isUnauthorized());
        }
    }
}
