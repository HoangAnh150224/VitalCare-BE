package com.vn.vitalcare.care.registration.controller;

import com.vn.vitalcare.care.registration.dto.OtpRequest;
import com.vn.vitalcare.care.registration.dto.OtpResponse;
import com.vn.vitalcare.care.registration.dto.RegisterRequest;
import com.vn.vitalcare.care.registration.service.RegistrationService;
import com.vn.vitalcare.identity.auth.dto.TokenResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/auth/register} — creating an account without an administrator.
 *
 * <p>Under {@code /api/auth} beside sign-in, and open in {@code SecurityConfig}
 * for the same reason: it is what you do before you hold a token.
 */
@RestController
@RequestMapping("/api/auth/register")
public class RegistrationController {

    private final RegistrationService service;

    public RegistrationController(RegistrationService service) {
        this.service = service;
    }

    @PostMapping("/otp")
    public OtpResponse requestCode(@Valid @RequestBody OtpRequest request) {
        return service.requestCode(request.phone());
    }

    /** Answers like a sign-in: the new account is signed in straight away. */
    @PostMapping
    public TokenResponse register(@Valid @RequestBody RegisterRequest request) {
        return service.register(request);
    }
}
