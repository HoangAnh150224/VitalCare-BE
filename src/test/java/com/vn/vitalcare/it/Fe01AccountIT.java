package com.vn.vitalcare.it;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vn.vitalcare.care.registration.entity.VerificationPurpose;
import com.vn.vitalcare.care.registration.service.OtpSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;

/**
 * FE-01 Authentication & RBAC, the functions after sign-in: Forgot / Reset / Change Password, User Profile and
 * Update User Profile, Access Denied, Users List, User Details, User Role and Account Status Management.
 * Real filter chain, services and PostgreSQL; only the SMS gateway is replaced, so the code it would text can be read.
 */
class Fe01AccountIT extends IntegrationTestBase {


    private ResultActions login(String phone, String password) throws Exception {
        return postJson("/api/auth/login", """
                {"phone":"%s","password":"%s"}""".formatted(phone, password));
    }

    private String token(String phone, String password) throws Exception {
        return json(login(phone, password).andExpect(status().isOk()), "$.accessToken");
    }

    private ResultActions as(String token, org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        return mvc.perform(request);
    }

    private String codeTextedTo(String intlPhone) {
        assertEquals(VerificationPurpose.RESET_PASSWORD, texted.purposeOf(intlPhone));
        return texted.last(intlPhone);
    }

    private Long userId(String intlPhone) {
        return jdbc.queryForObject("select id from users where phone = ?", Long.class, intlPhone);
    }

    // ------------------------------------------------------------------ Forgot Password / Reset Password
    @Nested
    class ForgotAndReset {

        @Test
        void pwd01_codeTextedToAnAccountsNumber() throws Exception {
            String phone = newPhone();
            register(phone, "Nguyen Van A", "Passw0rd!");
            postJson("/api/auth/password-reset/otp", """
                    {"phone":"%s"}""".formatted(phone))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.expiresIn").value(300))
                    .andExpect(jsonPath("$.resendAfter").value(60));
            assertEquals(6, codeTextedTo(intl(phone)).length());
            assertEquals(1, count("select count(*) from phone_verification where phone = ? and purpose = 'RESET_PASSWORD'", intl(phone)));
        }

        @Test
        void pwd02_unknownNumberSameAnswerNoSms() throws Exception {
            String phone = newPhone();
            postJson("/api/auth/password-reset/otp", """
                    {"phone":"%s"}""".formatted(phone))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.expiresIn").value(300));
            assertEquals(null, texted.last(phone));
        }

        @Test
        void pwd03_secondCodeWithinAMinuteRefused() throws Exception {
            String phone = newPhone();
            register(phone, "Nguyen Van A", "Passw0rd!");
            postJson("/api/auth/password-reset/otp", """
                    {"phone":"%s"}""".formatted(phone)).andExpect(status().isOk());
            postJson("/api/auth/password-reset/otp", """
                    {"phone":"%s"}""".formatted(phone))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("Please wait")));
        }

        @Test
        void pwd04_wrongCodeIs422OnTheOtpField() throws Exception {
            String phone = newPhone();
            register(phone, "Nguyen Van A", "Passw0rd!");
            postJson("/api/auth/password-reset/otp", """
                    {"phone":"%s"}""".formatted(phone)).andExpect(status().isOk());
            String code = codeTextedTo(intl(phone));
            String wrong = code.equals("000000") ? "111111" : "000000";
            postJson("/api/auth/password-reset/otp/check", """
                    {"phone":"%s","otp":"%s"}""".formatted(phone, wrong))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors.otp").value("The code is incorrect or has expired"));
            sync();
            assertEquals(1, count("select attempts from phone_verification where phone = ? and purpose = 'RESET_PASSWORD'", intl(phone)));
        }

        @Test
        void pwd05_fullRecoveryChangesThePasswordAndEndsSessions() throws Exception {
            String phone = newPhone();
            String oldRefresh = json(registerWith(phone, "Nguyen Van A", "Passw0rd!"), "$.refreshToken");
            postJson("/api/auth/password-reset/otp", """
                    {"phone":"%s"}""".formatted(phone)).andExpect(status().isOk());
            String code = codeTextedTo(intl(phone));
            postJson("/api/auth/password-reset/otp/check", """
                    {"phone":"%s","otp":"%s"}""".formatted(phone, code))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(true));
            postJson("/api/auth/password-reset", """
                    {"phone":"%s","otp":"%s","newPassword":"NewPassw0rd!"}""".formatted(phone, code))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
            login(phone, "Passw0rd!").andExpect(status().isUnauthorized());
            login(phone, "NewPassw0rd!").andExpect(status().isOk());
            postJson("/api/auth/refresh", """
                    {"refreshToken":"%s"}""".formatted(oldRefresh)).andExpect(status().isUnauthorized());
        }

        @Test
        void pwd06_codeWorksOnce() throws Exception {
            String phone = newPhone();
            register(phone, "Nguyen Van A", "Passw0rd!");
            postJson("/api/auth/password-reset/otp", """
                    {"phone":"%s"}""".formatted(phone)).andExpect(status().isOk());
            String code = codeTextedTo(intl(phone));
            postJson("/api/auth/password-reset", """
                    {"phone":"%s","otp":"%s","newPassword":"NewPassw0rd!"}""".formatted(phone, code)).andExpect(status().isOk());
            postJson("/api/auth/password-reset", """
                    {"phone":"%s","otp":"%s","newPassword":"Another1!"}""".formatted(phone, code))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors.otp").exists());
        }
    }

    // ------------------------------------------------------------------ Change Password
    @Nested
    class ChangePassword {

        private ResultActions change(String token, String current, String next) throws Exception {
            return mvc.perform(post("/api/auth/change-password").header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"currentPassword":"%s","newPassword":"%s"}""".formatted(current, next)));
        }

        @Test
        void cpw01_changeKeepsThisSessionAndEndsTheOthers() throws Exception {
            String phone = newPhone();
            String other = json(registerWith(phone, "Nguyen Van A", "Passw0rd!"), "$.refreshToken");
            String token = token(phone, "Passw0rd!");
            ResultActions r = change(token, "Passw0rd!", "NewPassw0rd!").andExpect(status().isOk());
            String fresh = json(r, "$.accessToken");
            mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + fresh)).andExpect(status().isOk());
            postJson("/api/auth/refresh", """
                    {"refreshToken":"%s"}""".formatted(other)).andExpect(status().isUnauthorized());
            login(phone, "NewPassw0rd!").andExpect(status().isOk());
            login(phone, "Passw0rd!").andExpect(status().isUnauthorized());
        }

        @Test
        void cpw02_wrongCurrentPasswordIs409() throws Exception {
            String phone = newPhone();
            String token = register(phone, "Nguyen Van A", "Passw0rd!");
            change(token, "Wrong123!", "NewPassw0rd!")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("The current password is not correct"));
            login(phone, "Passw0rd!").andExpect(status().isOk());
        }

        @Test
        void cpw03_tooShortIs422_andNoTokenIs401() throws Exception {
            String token = register(newPhone(), "Nguyen Van A", "Passw0rd!");
            change(token, "Passw0rd!", "short").andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors.newPassword").value("New password must be between 8 and 72 characters"));
            mvc.perform(post("/api/auth/change-password").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"currentPassword\":\"a\",\"newPassword\":\"NewPassw0rd!\"}"))
                    .andExpect(status().isUnauthorized());
        }

        /** Over BCrypt's 72 bytes is refused as a validation error (DEF-AUTH-01 fixed); the old password still works. */
        @Test
        void cpw04_passwordOver72Bytes() throws Exception {
            String phone = newPhone();
            String token = register(phone, "Nguyen Van A", "Passw0rd!");
            change(token, "Passw0rd!", "a".repeat(80)).andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors.newPassword").exists());
            login(phone, "Passw0rd!").andExpect(status().isOk());
        }
    }

    // ------------------------------------------------------------------ User Profile / Update User Profile
    @Nested
    class Profile {

        @Test
        void pro01_meShowsTheAccountWithoutSecrets() throws Exception {
            String token = token("0900000011", "Staff@123");
            mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.phone").value("+84900000011"))
                    .andExpect(jsonPath("$.fullName").value("BS. Nguyễn Minh Khoa"))
                    .andExpect(jsonPath("$.passwordHash").doesNotExist());
            mvc.perform(get("/api/auth/permissions").header("Authorization", "Bearer " + token))
                    .andExpect(jsonPath("$.roles", contains("DOCTOR")));
        }

        @Test
        void pro02_customerUpdatesOwnProfile() throws Exception {
            String token = token("0900000501", "Staff@123");
            Long own = jdbc.queryForObject("select c.id from customer c join users u on u.id = c.user_id where u.phone = '+84900000501'", Long.class);
            mvc.perform(get("/api/my_profiles").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(own));
            mvc.perform(patch("/api/my_profiles/" + own).header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON).content("""
                            {"dateOfBirth":"1991-03-14","gender":"female","nationalId":"001191004521","nationalIdType":"cccd",
                             "province":"Hà Nội","ward":"Phường Hàng Bài","address":"12 Phố Huế"}"""))
                    .andExpect(status().isOk());
            sync();
            assertEquals("12 Phố Huế", jdbc.queryForObject("select address from customer where id = ?", String.class, own));
        }

        @Test
        void pro03_someoneElsesProfileIs404_invalidIs422() throws Exception {
            String token = token("0900000501", "Staff@123");
            String other = register(newPhone(), "Khach Khac", "Passw0rd!");
            sync();
            Long otherId = jdbc.queryForObject("select max(id) from customer", Long.class);
            mvc.perform(patch("/api/my_profiles/" + otherId).header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON).content("""
                            {"dateOfBirth":"1990-01-01","gender":"male","nationalId":"001190000001",
                             "province":"Hà Nội","ward":"Phường Hàng Bài","address":"1 Tràng Tiền"}"""))
                    .andExpect(status().isNotFound());
            mvc.perform(patch("/api/my_profiles/" + otherId).header("Authorization", "Bearer " + other)
                    .contentType(MediaType.APPLICATION_JSON).content("""
                            {"dateOfBirth":"2999-01-01","gender":"x","nationalId":""}"""))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors.dateOfBirth").value("Date of birth must be in the past"));
        }
    }

    // ------------------------------------------------------------------ Access Denied
    @Test
    void acd01_forbiddenIs403AndDoesNotEndTheSession() throws Exception {
        String token = token("0900000011", "Staff@123");
        mvc.perform(patch("/api/users/" + userId("+84900000012")).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"fullName\":\"X\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("You do not have permission to perform this action"));
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ Users List / User Details / Role / Status
    @Nested
    class Administration {

        private String admin;

        @BeforeEach
        void signIn() throws Exception {
            admin = token("0900000001", "Admin@123");
        }

        private ResultActions patchUser(Long id, String json) throws Exception {
            return mvc.perform(patch("/api/users/" + id).header("Authorization", "Bearer " + admin)
                    .contentType(MediaType.APPLICATION_JSON).content(json));
        }

        private Long roleId(String code) {
            return jdbc.queryForObject("select id from roles where code = ?", Long.class, code);
        }

        @Test
        void usr01_searchAndFilterTheList() throws Exception {
            mvc.perform(get("/api/users").param("q", "Minh Khoa").header("Authorization", "Bearer " + admin))
                    .andExpect(status().isOk())
                    .andExpect(header().exists("X-Total-Count"))
                    .andExpect(jsonPath("$[*].phone", hasItem("+84900000011")))
                    .andExpect(jsonPath("$[*].phone", not(hasItem("+84900000012"))));
            String phone = newPhone();
            register(phone, "Bi Khoa", "Passw0rd!");
            sync();
            patchUser(userId(intl(phone)), "{\"status\":\"locked\"}").andExpect(status().isOk());
            mvc.perform(get("/api/users").param("status", "locked").header("Authorization", "Bearer " + admin))
                    .andExpect(jsonPath("$[*].phone", hasItem(intl(phone))))
                    .andExpect(jsonPath("$[*].status", not(hasItem("active"))));
        }

        @Test
        void usr02_detailsShowRoleAndStatusButNoPassword_unknownIs404() throws Exception {
            mvc.perform(get("/api/users/" + userId("+84900000012")).header("Authorization", "Bearer " + admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.fullName").value("ĐD. Trần Thu Hà"))
                    .andExpect(jsonPath("$.status").value("active"))
                    .andExpect(jsonPath("$.roles[0].code").value("MANAGER"))
                    .andExpect(jsonPath("$.passwordHash").doesNotExist());
            mvc.perform(get("/api/users/99999999").header("Authorization", "Bearer " + admin))
                    .andExpect(status().isNotFound());
        }

        @Test
        void rol01_newRoleTakesEffectOnTheNextRequest() throws Exception {
            String phone = newPhone();
            String userToken = register(phone, "Nhan Vien Moi", "Passw0rd!");
            sync();
            // The set of roles is fixed: no new role can be added...
            mvc.perform(post("/api/roles").header("Authorization", "Bearer " + admin)
                    .contentType(MediaType.APPLICATION_JSON).content("""
                            {"code":"IT_REVIEWER","name":"Người duyệt IT","permissions":[]}"""))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("The roles are fixed; a new one cannot be added"));
            // ...and a system role changes only through its own workflow (registration, activation, staff creation).
            patchUser(userId(intl(phone)), "{\"roles\":[{\"id\":%d}]}".formatted(roleId("DOCTOR")))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/auth/permissions").header("Authorization", "Bearer " + userToken))
                    .andExpect(jsonPath("$.roles", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("DOCTOR"))));
        }

        @Test
        void rol02_noRoleIs422() throws Exception {
            patchUser(userId("+84900000012"), "{\"roles\":[]}")
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors.roles").value("At least one role is required"));
        }

        @Test
        void sta01_lockAndUnlock() throws Exception {
            String phone = newPhone();
            String refresh = json(registerWith(phone, "Nguyen Van A", "Passw0rd!"), "$.refreshToken");
            sync();
            Long id = userId(intl(phone));
            patchUser(id, "{\"status\":\"locked\"}").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("locked"));
            login(phone, "Passw0rd!").andExpect(status().isUnauthorized());
            postJson("/api/auth/refresh", """
                    {"refreshToken":"%s"}""".formatted(refresh)).andExpect(status().isUnauthorized());
            patchUser(id, "{\"status\":\"active\"}").andExpect(status().isOk());
            login(phone, "Passw0rd!").andExpect(status().isOk());
        }

        @Test
        void sta02_lastAdministratorCannotBeLocked() throws Exception {
            jdbc.update("update users set status = 'INACTIVE' where id in (select ur.user_id from user_roles ur "
                    + "join roles r on r.id = ur.role_id where r.code = 'ADMIN') and phone <> '+84900000001'");
            patchUser(userId("+84900000001"), "{\"status\":\"locked\"}")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("The single System Administrator account cannot be")));
        }
    }
}
