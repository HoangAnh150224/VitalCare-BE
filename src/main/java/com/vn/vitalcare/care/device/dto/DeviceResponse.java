package com.vn.vitalcare.care.device.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.vn.vitalcare.entity.DeviceAssignment;
import com.vn.vitalcare.entity.DeviceStatus;
import com.vn.vitalcare.entity.MedicalDevice;
import com.vn.vitalcare.identity.rowlevel.service.RowLevelSecurity;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * A device, and who is wearing it now if anybody — what the device list is
 * read for.
 */
public record DeviceResponse(
        Long id,
        String deviceCode,
        String serialNumber,
        String deviceType,
        String manufacturer,
        String model,
        DeviceStatus status,
        OffsetDateTime lastSeenAt,
        OffsetDateTime registeredAt,
        UUID clinicId,
        Wearer currentPatient,
        /**
         * What the caller may do to this row, from the row-level write and
         * delete policies — the framework's {@code _can}, read by the client's
         * access control to hide buttons that would end in a 404 or 422. Empty
         * when there is no scope to report against.
         */
        @JsonProperty("_can")
        Map<String, Boolean> can) {

    /** The patient wearing the device, and since when. */
    public record Wearer(Long customerId, String customerCode, String fullName, OffsetDateTime since) {
    }

    /** Without row flags — for a caller with no scope to report against. */
    public static DeviceResponse from(MedicalDevice device, DeviceAssignment open) {
        return from(device, open, null);
    }

    public static DeviceResponse from(MedicalDevice device, DeviceAssignment open, RowLevelSecurity rowLevel) {
        Wearer wearer = null;
        if (open != null) {
            var customer = open.getCustomer();
            wearer = new Wearer(
                    customer.getId(), customer.getCustomerCode(), customer.getUser().getFullName(), open.getAssignedAt());
        }
        return new DeviceResponse(
                device.getId(),
                device.getDeviceCode(),
                device.getSerialNumber(),
                device.getDeviceType(),
                device.getManufacturer(),
                device.getModel(),
                device.getStatus(),
                device.getLastSeenAt(),
                device.getRegisteredAt(),
                // The id only: it reads off the proxy without loading the clinic.
                device.getClinic() == null ? null : device.getClinic().getClinicId(),
                wearer,
                rowLevel == null ? Map.of() : rowLevel.capabilities(device));
    }
}
