package com.vn.vitalcare.care.registration.controller;

import com.vn.vitalcare.care.registration.dto.OtpRequest;
import com.vn.vitalcare.care.registration.dto.OtpResponse;
import com.vn.vitalcare.care.registration.dto.PasswordResetRequest;
import com.vn.vitalcare.care.registration.service.PasswordResetService;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/auth/password-reset} — a forgotten password, recovered with a
 * code sent to the number the account signs in with.
 *
 * <p>Open in {@code SecurityConfig}, like registration: somebody who has
 * forgotten their password holds no token.
 */
@RestController
@RequestMapping("/api/auth/password-reset")
public class PasswordResetController {

    private final PasswordResetService service;

    public PasswordResetController(PasswordResetService service) {
        this.service = service;
    }

    /** The same answer whether or not the number has an account. */
    @PostMapping("/otp")
    public OtpResponse requestCode(@Valid @RequestBody OtpRequest request) {
        return service.requestCode(request.phone());
    }

    /** A body rather than {@code 204}, for the same reason as {@code /auth/logout}. */
    @PostMapping
    public Map<String, Object> reset(@Valid @RequestBody PasswordResetRequest request) {
        service.reset(request);
        return Map.of("success", true);
    }
}
