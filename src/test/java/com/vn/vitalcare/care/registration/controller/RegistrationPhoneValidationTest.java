package com.vn.vitalcare.care.registration.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vn.vitalcare.care.registration.dto.OtpRequest;
import com.vn.vitalcare.care.registration.service.RegistrationService;
import com.vn.vitalcare.share.exception.FieldValidationException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class RegistrationPhoneValidationTest {

    @Autowired
    private Validator validator;

    @Autowired
    private RegistrationService registration;

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "01234567891|Phone number has too many digits",
            "1234|Phone number has too few digits",
            "09abc12345|Phone number may only contain digits, spaces and a leading +",
            "09@1234567|Phone number may only contain digits, spaces and a leading +",
            "+12025550123|Phone number must be a Vietnamese number, such as 0901234567 or +84901234567",
            "'   '|Phone number is required"
    })
    void invalidPhoneGetsASpecificFieldErrorBeforeAnOtpCanBeSent(String phone, String expectedMessage) {
        Set<ConstraintViolation<OtpRequest>> violations = validator.validate(new OtpRequest(phone));
        if (phone.isBlank()) {
            assertThat(violations).extracting(ConstraintViolation::getMessage)
                    .containsExactly("Phone number is required");
        } else {
            assertThat(violations).isEmpty();
        }

        assertThatThrownBy(() -> registration.requestCode(phone))
                .isInstanceOfSatisfying(FieldValidationException.class, exception -> {
                    assertThat(exception.field()).isEqualTo("phone");
                    assertThat(exception.getMessage()).isEqualTo(expectedMessage);
                });
    }
}
