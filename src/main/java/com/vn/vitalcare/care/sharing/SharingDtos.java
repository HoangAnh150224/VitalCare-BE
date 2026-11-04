package com.vn.vitalcare.care.sharing;

import com.vn.vitalcare.entity.SharingPermission;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

/** BF-06 shapes. */
public final class SharingDtos {

    private SharingDtos() {
    }

    /** {@code status}: ACTIVE, EXPIRED (past its date) or REVOKED. */
    public record PermissionResponse(Long id, Long customerId, String customerName, Long caregiverUserId,
                                     String caregiverName, String caregiverPhone, boolean shareVitals,
                                     boolean shareAlerts, boolean shareAppointments, String emergencyPhone,
                                     OffsetDateTime expiresAt, String note, String status,
                                     OffsetDateTime createdAt, OffsetDateTime revokedAt) {
        static PermissionResponse of(SharingPermission p, OffsetDateTime now) {
            String status = SharingPermission.REVOKED.equals(p.getStatus()) ? "REVOKED"
                    : p.inForce(now) ? "ACTIVE" : "EXPIRED";
            return new PermissionResponse(p.getId(), p.getCustomer().getId(), p.getCustomer().getFullName(),
                    p.getCaregiver().getId(), p.getCaregiver().getFullName(), p.getCaregiver().getPhone(),
                    p.isShareVitals(), p.isShareAlerts(), p.isShareAppointments(), p.getEmergencyPhone(),
                    p.getExpiresAt(), p.getNote(), status,
                    p.getCreatedAt() == null ? null : p.getCreatedAt().atOffset(java.time.ZoneOffset.UTC),
                    p.getRevokedAt());
        }
    }

    /** A grant: {@code profileId} picks one of the account's profiles (the own one if left out). */
    public record GrantRequest(Long profileId,
                               @NotBlank(message = "Enter the caregiver's phone number") String caregiverPhone,
                               Boolean shareVitals, Boolean shareAlerts, Boolean shareAppointments,
                               @NotBlank(message = "Enter an emergency contact number") String emergencyPhone,
                               OffsetDateTime expiresAt,
                               @Size(max = 500) String note) {
    }

    public record UpdateRequest(Boolean shareVitals, Boolean shareAlerts, Boolean shareAppointments,
                                String emergencyPhone, OffsetDateTime expiresAt, Boolean clearExpiry,
                                @Size(max = 500) String note) {
    }

    /** What a caregiver sees of a patient who shared with them. */
    public record SharedPatient(Long permissionId, Long customerId, String customerCode, String fullName,
                                boolean shareVitals, boolean shareAlerts, boolean shareAppointments,
                                OffsetDateTime expiresAt) {
        static SharedPatient of(SharingPermission p) {
            return new SharedPatient(p.getId(), p.getCustomer().getId(), p.getCustomer().getCustomerCode(),
                    p.getCustomer().getFullName(), p.isShareVitals(), p.isShareAlerts(), p.isShareAppointments(),
                    p.getExpiresAt());
        }
    }
}
