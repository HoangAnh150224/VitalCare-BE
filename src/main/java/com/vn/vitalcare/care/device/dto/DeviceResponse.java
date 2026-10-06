package com.vn.vitalcare.care.device.dto;

import com.vn.vitalcare.entity.DeviceAssignment;
import com.vn.vitalcare.entity.DeviceStatus;
import com.vn.vitalcare.entity.MedicalDevice;
import java.time.OffsetDateTime;

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
        Wearer currentPatient) {

    /** The patient wearing the device, and since when. */
    public record Wearer(Long customerId, String customerCode, String fullName, OffsetDateTime since) {
    }

    public static DeviceResponse from(MedicalDevice device, DeviceAssignment open) {
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
                wearer);
    }
}
