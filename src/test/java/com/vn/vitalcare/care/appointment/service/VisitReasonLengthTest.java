package com.vn.vitalcare.care.appointment.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.vn.vitalcare.care.appointment.dto.AppointmentUpdateRequest;
import com.vn.vitalcare.care.appointment.dto.BookingRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** KAN-88: a visit's reason may run to 500 characters, Vietnamese included; one more is refused. */
class VisitReasonLengthTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private static BookingRequest booking(String reason) {
        return new BookingRequest(null, null, null, LocalDate.now(), reason);
    }

    @Test
    void fiveHundredCharactersAreKeptAndOneMoreIsRefused() {
        assertThat(validator.validate(booking("đ".repeat(500)))).isEmpty();
        assertThat(validator.validate(booking("đ".repeat(501))))
                .extracting(ConstraintViolation::getMessage)
                .containsExactly("Reason must be at most 500 characters");
        assertThat(validator.validate(new AppointmentUpdateRequest("a".repeat(500), null))).isEmpty();
        assertThat(validator.validate(new AppointmentUpdateRequest("a".repeat(501), null))).hasSize(1);
    }
}
