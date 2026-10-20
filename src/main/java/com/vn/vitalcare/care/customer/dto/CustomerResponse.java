package com.vn.vitalcare.care.customer.dto;

import com.vn.vitalcare.entity.Customer;
import com.vn.vitalcare.entity.CustomerStatus;
import com.vn.vitalcare.entity.PatientActivationSource;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * A customer as the staff screens show it.
 *
 * <p>An account owner's name and number come from the account rather than
 * being stored twice: {@code users} owns them, and the sign-in number in
 * particular must never have a second copy that could disagree. A dependent
 * has no account and carries their own.
 */
public record CustomerResponse(
        Long id,
        String customerCode,
        CustomerStatus status,
        /** The profile's own account; null for a dependent, who has none. */
        Long userId,
        /** Whose relative this is, for a dependent; null on an account owner's profile. */
        GuardianSummary guardian,
        String relationship,
        String nationalId,
        String nationalIdType,
        LocalDate nationalIdIssuedOn,
        String ethnicity,
        String nationality,
        String occupation,
        String province,
        String ward,
        String fullName,
        String phone,
        String email,
        LocalDate dateOfBirth,
        String gender,
        String address,
        String emergencyContactName,
        String emergencyContactPhone,
        /** active, inactive or paused (SRS 3.8.5). */
        String activityStatus,
        String activityReason,
        OffsetDateTime activityChangedAt,
        /** Who made the last change; filled on a single customer's answer only. */
        String activityChangedBy,
        OffsetDateTime patientActivatedAt,
        PatientActivationSource patientActivationSource,
        Instant createdAt) {

    public record GuardianSummary(Long id, String customerCode, String fullName, String phone) {
    }

    public static CustomerResponse from(Customer customer) {
        return from(customer, null);
    }

    /** @param activityChangedBy the name of whoever last changed the activity status */
    public static CustomerResponse from(Customer customer, String activityChangedBy) {
        var user = customer.getUser();
        var guardian = customer.getGuardian();
        return new CustomerResponse(
                customer.getId(),
                customer.getCustomerCode(),
                customer.getStatus(),
                user == null ? null : user.getId(),
                guardian == null ? null : new GuardianSummary(
                        guardian.getId(), guardian.getCustomerCode(), guardian.getFullName(), guardian.getPhone()),
                customer.getRelationship(),
                customer.getNationalId(),
                customer.getNationalIdType(),
                customer.getNationalIdIssuedOn(),
                customer.getEthnicity(),
                customer.getNationality(),
                customer.getOccupation(),
                customer.getProvince(),
                customer.getWard(),
                customer.getFullName(),
                customer.getPhone(),
                user == null ? null : user.getEmail(),
                customer.getDateOfBirth(),
                customer.getGender(),
                customer.getAddress(),
                customer.getEmergencyContactName(),
                customer.getEmergencyContactPhone(),
                customer.getActivityStatus(),
                customer.getActivityReason(),
                customer.getActivityChangedAt(),
                activityChangedBy,
                customer.getPatientActivatedAt(),
                customer.getPatientActivationSource(),
                customer.getCreatedAt());
    }
}
