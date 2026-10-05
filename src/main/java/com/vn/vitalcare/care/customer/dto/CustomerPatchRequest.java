package com.vn.vitalcare.care.customer.dto;

import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * {@code PATCH /customers/{id}}: the profile details the clinic keeps.
 *
 * <p>Null leaves a field alone; an empty string clears a text field. Name and
 * phone are not here — they belong to the account and are changed on the user
 * screen, where changing the sign-in number also ends open sessions. Status is
 * not here either: becoming a patient is its own action.
 */
public record CustomerPatchRequest(
        @Past(message = "Date of birth must be in the past")
        LocalDate dateOfBirth,

        @Pattern(regexp = "^(male|female|other)?$", message = "Gender must be male, female or other")
        String gender,

        @Size(max = 500, message = "Address must be at most 500 characters")
        String address,

        @Size(max = 255, message = "Emergency contact name must be at most 255 characters")
        String emergencyContactName,

        @Size(max = 50, message = "Emergency contact phone must be at most 50 characters")
        String emergencyContactPhone) {
}
