package com.vn.vitalcare.care.appointment.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * {@code POST /appointments/walk-in}: somebody at the counter to be seen
 * today. Either a customer the clinic knows ({@code customerId}) or a new
 * patient, who is given an account on their phone number there and then — they
 * sign in later through "forgot password".
 */
public record WalkInRequest(
        Long customerId,

        @Valid
        NewPatient newPatient,

        /** Ignored: a walk-in waits in the clinic's shared line; the room that calls it in gives its doctor. */
        Long employeeId,

        /** Which clinic, when there is more than one. */
        java.util.UUID clinicId,

        @NotBlank(message = "A reason for the visit is required")
        @Size(max = 255, message = "Reason must be at most 255 characters")
        String reason,

        @Size(max = 2000, message = "Note must be at most 2000 characters")
        String note) {

    /** What the desk takes down for somebody new: enough to register a visit. */
    public record NewPatient(
            @NotBlank(message = "Full name is required")
            @Size(max = 255, message = "Full name must be at most 255 characters")
            String fullName,

            @NotBlank(message = "Phone number is required")
            @Pattern(regexp = "^[\\s\\h.()-]*(?:\\+?84|0)(?:[\\s\\h.()-]*\\d){9}[\\s\\h.()-]*$",
                    message = "Phone number must be a Vietnamese number, such as 0901234567 or +84901234567")
            String phone,

            @NotNull(message = "Date of birth is required")
            LocalDate dateOfBirth,

            @NotBlank(message = "Gender is required")
            @Pattern(regexp = "male|female|other", message = "Gender must be male, female or other")
            String gender) {
    }

    public StaffBookingRequest booking(Long customer, LocalDate today) {
        return new StaffBookingRequest(customer, employeeId, clinicId, today, reason, note);
    }
}
