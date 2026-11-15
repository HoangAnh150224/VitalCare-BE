package com.vn.vitalcare.care.device.dto;

import com.vn.vitalcare.entity.DeviceStatus;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;

/**
 * {@code PATCH /devices/{id}}. The code is not here: it is what the device is
 * known by, on its label and in its history.
 *
 * <p>{@code status} may move a device between available, maintenance and
 * retired; handing it to a patient and taking it back are what move it in and
 * out of assigned.
 */
public record DevicePatchRequest(
        @Pattern(regexp = "(?s).*\\S.*", message = "Vui lòng nhập số serial.")
        @Size(max = 128, message = "Serial number must be at most 128 characters")
        String serialNumber,

        @Pattern(regexp = "(?s).*\\S.*", message = "Vui lòng nhập loại thiết bị.")
        @Size(max = 128, message = "Device type must be at most 128 characters")
        String deviceType,

        @Pattern(regexp = "(?s).*\\S.*", message = "Vui lòng nhập hãng sản xuất.")
        @Size(max = 255, message = "Manufacturer must be at most 255 characters")
        String manufacturer,

        @Pattern(regexp = "(?s).*\\S.*", message = "Vui lòng nhập model.")
        @Size(max = 255, message = "Model must be at most 255 characters")
        String model,

        DeviceStatus status) {
}
