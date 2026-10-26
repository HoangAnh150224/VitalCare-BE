package com.vn.vitalcare.care.registration.service.impl;

import com.vn.vitalcare.care.customer.service.CustomerService;
import com.vn.vitalcare.care.registration.dto.RegisterRequest;
import com.vn.vitalcare.care.registration.service.RegistrationService;
import com.vn.vitalcare.identity.auth.dto.TokenResponse;
import com.vn.vitalcare.identity.auth.service.AuthService;
import com.vn.vitalcare.identity.user.entity.User;
import com.vn.vitalcare.identity.user.service.UserService;
import com.vn.vitalcare.share.exception.FieldValidationException;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The {@link RegistrationService} the application runs on. */
@Service
@Transactional(readOnly = true)
public class RegistrationServiceImpl implements RegistrationService {

    /** The system role every self-registered account receives; seeded by 013. */
    public static final String CUSTOMER_ROLE = "CUSTOMER";

    /** BCrypt ignores — and the encoder refuses — anything past this many bytes. */
    private static final int MAX_PASSWORD_BYTES = 72;

    private final UserService userService;
    private final CustomerService customerService;
    private final AuthService authService;

    public RegistrationServiceImpl(UserService userService,
                                   CustomerService customerService,
                                   AuthService authService) {
        this.userService = userService;
        this.customerService = customerService;
        this.authService = authService;
    }

    @Override
    @Transactional
    public TokenResponse register(RegisterRequest request) {
        if (request.password().getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new FieldValidationException("password", "Password is too long; use fewer accented letters");
        }

        // UserService normalises the number and email, and refuses either if
        // another account already holds it.
        User user = userService.createSelfRegistered(
                request.phone(), request.email(), request.password(), request.fullName(), CUSTOMER_ROLE);
        customerService.createNeutral(user);
        return authService.signInVerified(user);
    }
}
