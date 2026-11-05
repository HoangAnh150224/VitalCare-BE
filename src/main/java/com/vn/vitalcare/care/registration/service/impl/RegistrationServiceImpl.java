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

    private final UserService userService;
    private final CustomerService customerService;
    private final AuthService authService;
    private final PhoneCodes codes;

    public RegistrationServiceImpl(UserService userService,
                                   CustomerService customerService,
                                   AuthService authService,
                                   PhoneCodes codes) {
        this.userService = userService;
        this.customerService = customerService;
        this.authService = authService;
        this.codes = codes;
    }

    @Override
    @Transactional
    public OtpResponse requestCode(String rawPhone) {
        String phone = normalize(rawPhone);
        return codes.issue(phone, VerificationPurpose.REGISTER, phone);
    }

    @Override
    @Transactional(noRollbackFor = InvalidVerificationCodeException.class)
    public TokenResponse register(RegisterRequest request) {
        String phone = normalize(request.phone());
        if (request.password().isBlank()) {
            throw new FieldValidationException("password", "Password cannot contain only spaces");
        }
        if (request.password().getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new FieldValidationException("password", "Password is too long; use fewer accented letters");
        }

        codes.verify(phone, VerificationPurpose.REGISTER, request.otp());

        // UserService normalises the number and any email, and refuses either if
        // another account already holds it.
        User user = userService.createSelfRegistered(
                phone, request.email(), request.password(), request.fullName(), CUSTOMER_ROLE);
        customerService.createNeutral(user);
        return authService.signInVerified(user);
    }

    @Override
    @Transactional
    public com.vn.vitalcare.entity.Customer registerAtDesk(String fullName, String phone,
                                                          java.time.LocalDate dateOfBirth, String gender) {
        byte[] secret = new byte[24];
        new java.security.SecureRandom().nextBytes(secret);
        String unknownPassword = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        User user = userService.createSelfRegistered(phone, null, unknownPassword, fullName.trim(), CUSTOMER_ROLE);
        com.vn.vitalcare.entity.Customer customer = customerService.createNeutral(user);
        customer.setDateOfBirth(dateOfBirth);
        customer.setGender(gender);
        return customer;
    }

    private static String normalize(String rawPhone) {
        return PhoneNumbers.normalize(rawPhone)
                .orElseThrow(() -> new FieldValidationException(
                        "phone", PhoneNumbers.problem(rawPhone).orElse("Not a valid Vietnamese phone number")));
    }
}
