package com.vn.vitalcare.care.iomt;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * One upload from a device or from the phone it is paired with — the same
 * shape over REST ({@code POST /api/device_data/batch},
 * {@code POST /api/my_devices/{code}/readings}) and MQTT
 * ({@code vitalcare/devices/{code}/vitals}). See docs/mobile-api.md.
 *
 * @param clientBatchId   the sender's id for this upload, echoed in the sync history
 * @param pendingOnDevice how many readings the sender still holds offline after this one
 * @param battery         battery percent, 0-100
 * @param session         the measurement sitting these readings belong to, when there is one
 */
public record DeviceUpload(
        @Size(max = 64) String clientBatchId,
        Integer pendingOnDevice,
        Integer battery,
        @Size(max = 64) String firmware,
        @Valid Session session,
        @NotNull @Size(max = 500, message = "At most 500 readings per upload") List<Reading> readings) {

    /**
     * One reading. Kept loose on purpose — a malformed field refuses this
     * reading, not the whole upload — and checked by {@link VitalIngestion}.
     *
     * @param clientRecordId  the device's own id for it; sent again, it is stored once
     * @param measuredAt      ISO-8601 with an offset, e.g. {@code 2026-11-02T08:15:00+07:00}
     * @param sessionId       the {@link Session#clientSessionId()} it was taken in, if any
     * @param skinTemperature wrist skin temperature, °C
     * @param bodyTemperature estimated body temperature, °C — only once the skin reading has settled
     * @param alerts          what the device itself found abnormal in this reading, by its own thresholds
     */
    public record Reading(String clientRecordId, String measuredAt, BigDecimal spo2, Integer heartRate,
                          BigDecimal skinTemperature, BigDecimal bodyTemperature, String sessionId,
                          List<DeviceAlert> alerts) {

        public Reading(String clientRecordId, String measuredAt, BigDecimal spo2, Integer heartRate,
                       BigDecimal skinTemperature, BigDecimal bodyTemperature, String sessionId) {
            this(clientRecordId, measuredAt, spo2, heartRate, skinTemperature, bodyTemperature, sessionId, null);
        }
    }

    /**
     * The device's own verdict on one measure of a reading: its thresholds are
     * built into it, so it can raise the alarm while the server's rules are off.
     *
     * @param metric    HEART_RATE, SPO2, SKIN_TEMPERATURE or BODY_TEMPERATURE
     * @param severity  WARNING or CRITICAL
     * @param operator  BELOW or ABOVE the threshold; optional, for the wording
     * @param threshold the device's threshold; optional, for the wording
     */
    public record DeviceAlert(String metric, String severity, String operator, BigDecimal threshold) {
    }

    /** {@code status}: IN_PROGRESS while measuring, COMPLETED or CANCELLED to close it. */
    public record Session(@Size(max = 64) String clientSessionId, String startedAt, String endedAt, String status) {
    }
}
