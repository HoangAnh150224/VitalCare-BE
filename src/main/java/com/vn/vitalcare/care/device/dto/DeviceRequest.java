package com.vn.vitalcare.care.device.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** {@code POST /devices}: registering a device the clinic has received. */
public record DeviceRequest(
        @NotBlank(message = "Vui lòng nhập mã thiết bị.")
        @Size(max = 64, message = "Device code must be at most 64 characters")
        String deviceCode,

        @NotBlank(message = "Vui lòng nhập số serial.")
        @Size(max = 128, message = "Serial number must be at most 128 characters")
        String serialNumber,

        @NotBlank(message = "Vui lòng nhập loại thiết bị.")
        @Size(max = 128, message = "Device type must be at most 128 characters")
        String deviceType,

        @NotBlank(message = "Vui lòng nhập hãng sản xuất.")
        @Size(max = 255, message = "Manufacturer must be at most 255 characters")
        String manufacturer,

        @NotBlank(message = "Vui lòng nhập model.")
        @Size(max = 255, message = "Model must be at most 255 characters")
        String model) {
}
