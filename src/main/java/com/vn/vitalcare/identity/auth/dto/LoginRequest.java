package com.vn.vitalcare.identity.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * {@code POST /auth/login} payload.
 *
 * <p>Any spelling of the number is accepted — {@code 0901234567} and
 * {@code +84901234567} reach the same account, because the service normalises
 * before it looks anything up. A number that is no Vietnamese number at all is
 * refused as such (422 on {@code phone}); every other refusal says which it is
 * (no account, wrong password, locked), as the clinic asked. The registration
 * screen already tells whether a number has an account, so one message for
 * all of them hid nothing.
 */
public record LoginRequest(
        @NotBlank(message = "Phone number is required")
        String phone,

        @NotBlank(message = "Password is required")
        String password) {
}
