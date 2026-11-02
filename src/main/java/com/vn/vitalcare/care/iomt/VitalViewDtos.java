package com.vn.vitalcare.care.iomt;

import com.vn.vitalcare.entity.Customer;
import com.vn.vitalcare.entity.DeviceAssignment;
import com.vn.vitalcare.entity.MedicalDevice;
import com.vn.vitalcare.entity.VitalSigns;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** What the vital-sign screens read (FE-04). */
public final class VitalViewDtos {

    private VitalViewDtos() {
    }

    /** Whose readings a screen shows: enough to title it, nothing more. */
    public record Patient(Long customerId, String customerCode, String fullName) {

        static Patient of(Customer c) {
            return new Patient(c.getId(), c.getCustomerCode(), c.getFullName());
        }
    }

    /** One measurement as listed. */
    public record Reading(Long id, OffsetDateTime measuredAt, OffsetDateTime receivedAt, Integer heartRate,
                          BigDecimal spo2, BigDecimal skinTemperature, String source, String validationStatus,
                          String validationError, Long deviceId, String deviceCode) {

        static Reading of(VitalSigns v) {
            MedicalDevice d = v.getDevice();
            return new Reading(v.getId(), v.getMeasuredAt(), v.getReceivedAt(), v.getHeartRate(), v.getSpo2(),
                    v.getTemperature(), v.getSource(), v.getValidationStatus(), v.getValidationError(),
                    d == null ? null : d.getId(), d == null ? null : d.getDeviceCode());
        }
    }

    /** One measurement in full (Measurement Details). */
    public record Detail(Long id, OffsetDateTime measuredAt, OffsetDateTime receivedAt, Integer heartRate,
                         BigDecimal spo2, BigDecimal skinTemperature, String source, String validationStatus,
                         String validationError, String clientRecordId, Long deviceId, String deviceCode,
                         String deviceModel, Long customerId, String customerCode, String customerName,
                         Long sessionId, String sessionStatus, OffsetDateTime sessionStartedAt,
                         OffsetDateTime sessionEndedAt) {

        static Detail of(VitalSigns v) {
            MedicalDevice d = v.getDevice();
            Customer c = v.getCustomer();
            var s = v.getSession();
            return new Detail(v.getId(), v.getMeasuredAt(), v.getReceivedAt(), v.getHeartRate(), v.getSpo2(),
                    v.getTemperature(), v.getSource(), v.getValidationStatus(), v.getValidationError(),
                    v.getClientRecordId(), d == null ? null : d.getId(), d == null ? null : d.getDeviceCode(),
                    d == null ? null : d.getModel(), c == null ? null : c.getId(),
                    c == null ? null : c.getCustomerCode(), c == null ? null : c.getFullName(),
                    s == null ? null : s.getId(), s == null ? null : s.getStatus(),
                    s == null ? null : s.getStartedAt(), s == null ? null : s.getEndedAt());
        }
    }

    /** A patient's latest value of each measure (Monitoring Overview, Patient Monitoring Summary). */
    public record Latest(Integer heartRate, OffsetDateTime heartRateAt, BigDecimal spo2, OffsetDateTime spo2At,
                         BigDecimal skinTemperature, OffsetDateTime skinTemperatureAt, OffsetDateTime lastMeasuredAt,
                         long readings24h) {

        static final Latest NONE = new Latest(null, null, null, null, null, null, null, 0);

        static Latest of(VitalSignsRepository.LatestRow r) {
            return r == null ? NONE : new Latest(r.getHeartRate(), at(r.getHeartRateAt()), r.getSpo2(),
                    at(r.getSpo2At()), r.getSkinTemperature(), at(r.getSkinTemperatureAt()),
                    at(r.getLastMeasuredAt()), r.getReadings24h() == null ? 0 : r.getReadings24h());
        }

        private static OffsetDateTime at(java.time.Instant i) {
            return i == null ? null : i.atOffset(ZoneOffset.UTC);
        }
    }

    /** A monitored patient on the overview: who, which device, and the latest values. */
    public record Monitored(Long customerId, String customerCode, String customerName, Long deviceId,
                            String deviceCode, String connectivity, Integer batteryLevel, OffsetDateTime assignedAt,
                            Latest latest) {

        static Monitored of(DeviceAssignment a, Latest latest) {
            Customer c = a.getCustomer();
            MedicalDevice d = a.getDevice();
            return new Monitored(c.getId(), c.getCustomerCode(), c.getFullName(), d.getId(), d.getDeviceCode(),
                    d.getConnectivity(), d.getBatteryLevel(), a.getAssignedAt(), latest);
        }
    }

    /** One bucket of the trend: an hour or a day, in the clinic's local time. */
    public record TrendPoint(LocalDateTime at, long readings, Integer heartRateMin, BigDecimal heartRateAvg,
                             Integer heartRateMax, BigDecimal spo2Min, BigDecimal spo2Avg, BigDecimal spo2Max,
                             BigDecimal skinTemperatureMin, BigDecimal skinTemperatureAvg,
                             BigDecimal skinTemperatureMax) {

        static TrendPoint of(VitalSignsRepository.TrendRow r) {
            return new TrendPoint(r.getBucket(), r.getReadings() == null ? 0 : r.getReadings(),
                    r.getHeartRateMin(), round(r.getHeartRateAvg(), 0), r.getHeartRateMax(),
                    r.getSpo2Min(), round(r.getSpo2Avg(), 1), r.getSpo2Max(),
                    r.getSkinTemperatureMin(), round(r.getSkinTemperatureAvg(), 2), r.getSkinTemperatureMax());
        }

        private static BigDecimal round(BigDecimal v, int scale) {
            return v == null ? null : v.setScale(scale, RoundingMode.HALF_UP);
        }
    }

    public record Trend(String unit, OffsetDateTime from, OffsetDateTime to, java.util.List<TrendPoint> points) {
    }
}
