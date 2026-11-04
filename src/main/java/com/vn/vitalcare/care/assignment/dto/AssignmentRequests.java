package com.vn.vitalcare.care.assignment.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

/** The request bodies for putting a patient in somebody's care and on a device. */
public final class AssignmentRequests {

    private AssignmentRequests() {
    }

    /** {@code POST /customers/{id}/care-team}. */
    public record AssignStaff(
            @NotNull(message = "A member of staff is required")
            Long employeeId,

            @Size(max = 2000, message = "Note must be at most 2000 characters")
            String note) {
    }

    /** {@code POST /customers/{id}/devices}. */
    public record AssignDevice(
            @NotNull(message = "A device is required")
            Long deviceId,

            /** When the device is due back, if it is lent for a fixed period. */
            OffsetDateTime expectedReturnAt,

            @Size(max = 2000, message = "Note must be at most 2000 characters")
            String note) {
    }

    /** Ending a care-team assignment or taking a device back. The note is appended, not replaced. */
    public record End(
            @Size(max = 2000, message = "Note must be at most 2000 characters")
            String note) {
    }
}
