package com.vn.vitalcare.care.customer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * {@code POST} and {@code PATCH /my_profiles}: a patient profile as its
 * account's owner fills it in — their own, or a relative's.
 *
 * <p>Date of birth and gender are what a visit cannot be registered without.
 * Name, phone and relationship are a relative's only: the owner's own name
 * and number belong to the account, and are ignored here.
 */
public record ProfileRequest(
        @Size(max = 255, message = "Name must be at most 255 characters")
        String fullName,

        @Pattern(regexp = "^$|^[0-9+ ]{8,20}$", message = "Phone number is not valid")
        String phone,

        @NotNull(message = "Date of birth is required")
        @Past(message = "Date of birth must be in the past")
        LocalDate dateOfBirth,

        @NotBlank(message = "Gender is required")
        @Pattern(regexp = "^(male|female|other)$", message = "Gender must be male, female or other")
        String gender,

        @Pattern(regexp = "^$|^(child|parent|spouse|sibling|other)$",
                message = "Relationship must be child, parent, spouse, sibling or other")
        String relationship,

        @Size(max = 32, message = "National id must be at most 32 characters")
        String nationalId,

        @Size(max = 500, message = "Address must be at most 500 characters")
        String address) {
}
