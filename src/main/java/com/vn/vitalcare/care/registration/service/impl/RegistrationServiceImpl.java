package com.vn.vitalcare.care.registration.service.impl;

import com.vn.vitalcare.care.customer.service.CustomerService;
import com.vn.vitalcare.care.registration.dto.OtpResponse;
import com.vn.vitalcare.care.registration.dto.RegisterRequest;
import com.vn.vitalcare.care.registration.entity.VerificationPurpose;
import com.vn.vitalcare.care.registration.service.InvalidVerificationCodeException;
import com.vn.vitalcare.care.registration.service.PhoneCodes;
import com.vn.vitalcare.care.registration.service.RegistrationService;
import com.vn.vitalcare.identity.auth.dto.TokenResponse;
import com.vn.vitalcare.identity.auth.service.AuthService;
import com.vn.vitalcare.identity.user.entity.User;
import com.vn.vitalcare.identity.user.service.UserService;
import com.vn.vitalcare.share.exception.ConflictException;
import com.vn.vitalcare.share.exception.FieldValidationException;
import com.vn.vitalcare.share.phone.PhoneNumbers;
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

    private final PhoneCodes codes;
    private final UserService userService;
    private final CustomerService customerService;
    private final AuthService authService;

    public RegistrationServiceImpl(PhoneCodes codes,
                                   UserService userService,
                                   CustomerService customerService,
                                   AuthService authService) {
        this.codes = codes;
        this.userService = userService;
        this.customerService = customerService;
        this.authService = authService;
    }

    @Override
    @Transactional
    public OtpResponse requestCode(String rawPhone) {
        String phone = normalize(rawPhone);
        if (userService.phoneInUse(phone)) {
            throw new ConflictException("That phone number is already in use");
        }

        return codes.issue(phone, VerificationPurpose.REGISTER, true);
    }

    @Override
    @Transactional(noRollbackFor = InvalidVerificationCodeException.class)
    public TokenResponse register(RegisterRequest request) {
        String phone = normalize(request.phone());
        if (request.password().getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new FieldValidationException("password", "Password is too long; use fewer accented letters");
        }

        codes.verify(phone, VerificationPurpose.REGISTER, request.otp());

        User user = userService.createSelfRegistered(phone, request.password(), request.fullName(), CUSTOMER_ROLE);
        customerService.createNeutral(user);
        return authService.signInVerified(user);
    }

    /** Unreachable through HTTP: the DTO pattern only admits numbers that normalise. */
    private static String normalize(String raw) {
        return PhoneNumbers.normalize(raw)
                .orElseThrow(() -> new FieldValidationException("phone", "Not a valid Vietnamese phone number"));
    }
}
