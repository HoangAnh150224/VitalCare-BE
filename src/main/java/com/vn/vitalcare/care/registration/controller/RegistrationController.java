package com.vn.vitalcare.care.registration.controller;

import com.vn.vitalcare.care.registration.dto.RegisterRequest;
import com.vn.vitalcare.care.registration.dto.OtpCheckRequest;
import com.vn.vitalcare.care.registration.dto.OtpRequest;
import com.vn.vitalcare.care.registration.dto.OtpResponse;
import com.vn.vitalcare.care.registration.dto.ProfileMatch;
import com.vn.vitalcare.care.registration.service.OtpIpLimiter;
import com.vn.vitalcare.care.registration.service.RegistrationService;
import com.vn.vitalcare.identity.auth.dto.TokenResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/auth/register} — creating an account after phone verification.
 *
 * <p>Under {@code /api/auth} beside sign-in. It is open in {@code SecurityConfig}
 * because verification happens before the caller holds a token.
 */
@RestController
@RequestMapping("/api/auth/register")
public class RegistrationController {

    private final RegistrationService service;
    private final OtpIpLimiter ipLimiter;

    public RegistrationController(RegistrationService service, OtpIpLimiter ipLimiter) {
        this.service = service;
        this.ipLimiter = ipLimiter;
    }

    @PostMapping("/otp")
    public OtpResponse requestCode(@Valid @RequestBody OtpRequest request, HttpServletRequest http) {
        ipLimiter.check(OtpIpLimiter.clientIp(http));
        return service.requestCode(request.phone());
    }

    /**
     * The code checked without spending it, answered with the profiles that
     * number already has at the clinic (masked) for the person to pick their
     * own. A wrong code is a 422 on {@code otp}.
     */
    @PostMapping("/otp/check")
    public java.util.List<ProfileMatch> checkCode(@Valid @RequestBody OtpCheckRequest request) {
        return service.checkCode(request.phone(), request.otp());
    }

    /** Answers like a sign-in only after consuming a valid phone code. */
    @PostMapping
    public TokenResponse register(@Valid @RequestBody RegisterRequest request) {
        return service.register(request);
    }
}
