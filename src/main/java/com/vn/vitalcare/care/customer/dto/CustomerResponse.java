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
 * <p>Name and number come from the account rather than being stored twice:
 * {@code users} owns them, and the sign-in number in particular must never
 * have a second copy that could disagree.
 */
public record CustomerResponse(
        Long id,
        String customerCode,
        CustomerStatus status,
        Long userId,
        String fullName,
        String phone,
        String email,
        LocalDate dateOfBirth,
        String gender,
        String address,
        String emergencyContactName,
        String emergencyContactPhone,
        OffsetDateTime patientActivatedAt,
        PatientActivationSource patientActivationSource,
        Instant createdAt) {

    public static CustomerResponse from(Customer customer) {
        var user = customer.getUser();
        return new CustomerResponse(
                customer.getId(),
                customer.getCustomerCode(),
                customer.getStatus(),
                user.getId(),
                user.getFullName(),
                user.getPhone(),
                user.getEmail(),
                customer.getDateOfBirth(),
                customer.getGender(),
                customer.getAddress(),
                customer.getEmergencyContactName(),
                customer.getEmergencyContactPhone(),
                customer.getPatientActivatedAt(),
                customer.getPatientActivationSource(),
                customer.getCreatedAt());
    }
}
