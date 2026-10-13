package com.vn.vitalcare.care.customer.dto;

import com.vn.vitalcare.entity.Customer;
import com.vn.vitalcare.entity.CustomerStatus;
import java.time.LocalDate;

/** A patient profile as its account's owner sees it. */
public record ProfileResponse(
        Long id,
        String customerCode,
        /** The owner's own profile, as opposed to a relative's. */
        boolean self,
        String fullName,
        String phone,
        LocalDate dateOfBirth,
        String gender,
        /** What a relative is to the owner; null on the owner's own profile. */
        String relationship,
        String nationalId,
        String nationalIdType,
        LocalDate nationalIdIssuedOn,
        String ethnicity,
        String nationality,
        String occupation,
        String province,
        String ward,
        String address,
        CustomerStatus status,
        /** Whether a visit can be registered for this profile yet. */
        boolean complete) {

    public static ProfileResponse from(Customer profile) {
        return new ProfileResponse(
                profile.getId(),
                profile.getCustomerCode(),
                !profile.isDependent(),
                profile.getFullName(),
                profile.getPhone(),
                profile.getDateOfBirth(),
                profile.getGender(),
                profile.getRelationship(),
                profile.getNationalId(),
                profile.getNationalIdType(),
                profile.getNationalIdIssuedOn(),
                profile.getEthnicity(),
                profile.getNationality(),
                profile.getOccupation(),
                profile.getProvince(),
                profile.getWard(),
                profile.getAddress(),
                profile.getStatus(),
                profile.isCompleteForVisit());
    }
}
