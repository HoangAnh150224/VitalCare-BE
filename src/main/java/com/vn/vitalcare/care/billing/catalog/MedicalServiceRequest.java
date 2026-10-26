package com.vn.vitalcare.care.billing.catalog;

import com.vn.vitalcare.entity.ServiceCategory;
import com.vn.vitalcare.entity.ServiceStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** {@code POST|PATCH /medical_services}: a catalogue entry. A missing status keeps (or starts) it active. */
public record MedicalServiceRequest(
        @NotBlank(message = "A service code is required")
        @Size(max = 64, message = "The code must be at most 64 characters")
        String code,

        @NotBlank(message = "A name is required")
        @Size(max = 255, message = "The name must be at most 255 characters")
        String name,

        @Size(max = 2000, message = "The description must be at most 2000 characters")
        String description,

        @NotNull(message = "A price is required")
        @DecimalMin(value = "0", message = "The price cannot be negative")
        @Digits(integer = 12, fraction = 0, message = "The price is a whole number of đồng")
        BigDecimal price,

        @NotNull(message = "A category is required")
        ServiceCategory category,

        @NotNull(message = "The turnaround is required")
        @Min(value = 0, message = "The turnaround cannot be negative")
        @Max(value = 43200, message = "The turnaround is at most 30 days")
        Integer turnaroundMinutes,

        ServiceStatus status) {
}
