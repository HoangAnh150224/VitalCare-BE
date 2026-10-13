package com.vn.vitalcare.care.customer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * {@code POST} and {@code PATCH /my_profiles}: a patient profile as its
 * account's owner fills it in — their own, or a relative's.
 *
 * <p>Date of birth and gender are what a visit cannot be registered without,
 * and a profile is not saved without an identity number either.
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

        @NotBlank(message = "A citizen id or personal identification number is required")
        @Size(max = 32, message = "National id must be at most 32 characters")
        String nationalId,

        /** cccd when left out. */
        @Pattern(regexp = "^$|^(cccd|personal_id|passport)$", message = "Id type must be cccd, personal_id or passport")
        String nationalIdType,

        @PastOrPresent(message = "The issue date cannot be in the future")
        LocalDate nationalIdIssuedOn,

        @Size(max = 64, message = "Ethnicity must be at most 64 characters")
        String ethnicity,

        @Size(max = 64, message = "Nationality must be at most 64 characters")
        String nationality,

        @Size(max = 128, message = "Occupation must be at most 128 characters")
        String occupation,

        @Size(max = 128, message = "Province must be at most 128 characters")
        String province,

        @Size(max = 128, message = "Ward must be at most 128 characters")
        String ward,

        @Size(max = 500, message = "Address must be at most 500 characters")
        String address) {
}
