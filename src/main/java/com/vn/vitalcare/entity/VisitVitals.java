package com.vn.vitalcare.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.math.BigDecimal;

/**
 * Vital signs measured by hand at a visit, on the encounter's own row. Every
 * one is optional. The ranges are the same as the table's CHECKs (migration
 * 033): they catch a slip of the keyboard, not a sick patient. Readings from a
 * device are a different thing and live in {@code vital_signs}.
 *
 * <p>One type for the column mapping, the request and the response, so the
 * limits are written once.
 */
@Embeddable
public record VisitVitals(
        @Column(name = "temperature", precision = 4, scale = 1)
        @DecimalMin(value = "30", message = "Temperature must be between 30 and 45 °C")
        @DecimalMax(value = "45", message = "Temperature must be between 30 and 45 °C")
        @Digits(integer = 2, fraction = 1, message = "Temperature takes one decimal")
        BigDecimal temperature,

        @Column(name = "pulse")
        @Min(value = 20, message = "Pulse must be between 20 and 250 per minute")
        @Max(value = 250, message = "Pulse must be between 20 and 250 per minute")
        Integer pulse,

        @Column(name = "respiratory_rate")
        @Min(value = 4, message = "Respiratory rate must be between 4 and 80 per minute")
        @Max(value = 80, message = "Respiratory rate must be between 4 and 80 per minute")
        Integer respiratoryRate,

        @Column(name = "systolic_bp")
        @Min(value = 50, message = "Systolic pressure must be between 50 and 260 mmHg")
        @Max(value = 260, message = "Systolic pressure must be between 50 and 260 mmHg")
        Integer systolicBp,

        @Column(name = "diastolic_bp")
        @Min(value = 20, message = "Diastolic pressure must be between 20 and 200 mmHg")
        @Max(value = 200, message = "Diastolic pressure must be between 20 and 200 mmHg")
        Integer diastolicBp,

        @Column(name = "spo2")
        @Min(value = 50, message = "SpO2 must be between 50 and 100 %")
        @Max(value = 100, message = "SpO2 must be between 50 and 100 %")
        Integer spo2,

        @Column(name = "weight_kg", precision = 5, scale = 1)
        @DecimalMin(value = "0.5", message = "Weight must be between 0.5 and 400 kg")
        @DecimalMax(value = "400", message = "Weight must be between 0.5 and 400 kg")
        @Digits(integer = 3, fraction = 1, message = "Weight takes one decimal")
        BigDecimal weightKg,

        @Column(name = "height_cm", precision = 5, scale = 1)
        @DecimalMin(value = "30", message = "Height must be between 30 and 250 cm")
        @DecimalMax(value = "250", message = "Height must be between 30 and 250 cm")
        @Digits(integer = 3, fraction = 1, message = "Height takes one decimal")
        BigDecimal heightCm) {
}
