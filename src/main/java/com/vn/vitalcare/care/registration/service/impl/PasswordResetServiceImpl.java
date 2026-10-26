package com.vn.vitalcare.care.registration.service.impl;

import com.vn.vitalcare.care.registration.dto.OtpResponse;
import com.vn.vitalcare.care.registration.dto.PasswordResetRequest;
import com.vn.vitalcare.care.registration.entity.VerificationPurpose;
import com.vn.vitalcare.care.registration.service.InvalidVerificationCodeException;
import com.vn.vitalcare.care.registration.service.PasswordResetService;
import com.vn.vitalcare.care.registration.service.PhoneCodes;
import com.vn.vitalcare.identity.user.entity.User;
import com.vn.vitalcare.identity.user.service.UserService;
import com.vn.vitalcare.share.exception.FieldValidationException;
import com.vn.vitalcare.share.phone.PhoneNumbers;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The {@link PasswordResetService} the application runs on. */
@Service
@Transactional(readOnly = true)
public class PasswordResetServiceImpl implements PasswordResetService {

    /** BCrypt ignores — and the encoder refuses — anything past this many bytes. */
    private static final int MAX_PASSWORD_BYTES = 72;

    private final PhoneCodes codes;
    private final UserService userService;

    public PasswordResetServiceImpl(PhoneCodes codes, UserService userService) {
        this.codes = codes;
        this.userService = userService;
    }

    @Override
    @Transactional
    public OtpResponse requestCode(String rawPhone) {
        String phone = normalize(rawPhone);
        String email = account(phone).map(User::getEmail).filter(address -> !address.isBlank()).orElse(null);
        return codes.issue(phone, VerificationPurpose.RESET_PASSWORD, email);
    }

    @Override
    @Transactional(noRollbackFor = InvalidVerificationCodeException.class)
    public void reset(PasswordResetRequest request) {
        String phone = normalize(request.phone());
        if (request.newPassword().getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new FieldValidationException("newPassword", "Password is too long; use fewer accented letters");
        }

        codes.verify(phone, VerificationPurpose.RESET_PASSWORD, request.otp());

        // A code is only ever sent for a number with such an account, so this
        // misses only if the account was disabled or deleted since; that
        // answers like a bad code rather than saying which.
        User user = account(phone).orElseThrow(InvalidVerificationCodeException::new);
        userService.setPassword(user.getId(), request.newPassword());
    }

    private Optional<User> account(String phone) {
        return userService.findForAuthentication(phone).filter(User::canSignIn);
    }

    /** Unreachable through HTTP: the DTO pattern only admits numbers that normalise. */
    private static String normalize(String raw) {
        return PhoneNumbers.normalize(raw)
                .orElseThrow(() -> new FieldValidationException("phone", "Not a valid Vietnamese phone number"));
    }
}
