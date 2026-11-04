package com.vn.vitalcare.entity;

import com.vn.vitalcare.share.data.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * One reading from a monitoring device (FE-03): SpO₂, heart rate and wrist
 * skin temperature, for the patient the device was handed to at the time.
 *
 * <p>{@code clientRecordId} is the device's own id for the reading; the same
 * one sent twice — a retried upload, an MQTT redelivery — is stored once. A
 * reading that fails validation is kept with {@code INVALID} and its reason,
 * so it can be looked at, but is never shown or alerted on as a measurement.
 */
@Entity
@Table(name = "vital_signs")
public class VitalSigns extends BaseEntity {

    public static final String VALID = "VALID";
    public static final String INVALID = "INVALID";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "device_id")
    private MedicalDevice device;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "device_assignment_id")
    private DeviceAssignment deviceAssignment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id")
    private MeasurementSession session;

    @Column(name = "heart_rate")
    private Integer heartRate;

    @Column(name = "spo2", precision = 5, scale = 2)
    private BigDecimal spo2;

    /** Wrist skin temperature, °C. */
    @Column(name = "temperature", precision = 5, scale = 2)
    private BigDecimal temperature;

    @Column(name = "measured_at")
    private OffsetDateTime measuredAt;

    @Column(name = "received_at")
    private OffsetDateTime receivedAt;

    @Column(name = "client_record_id", length = 64)
    private String clientRecordId;

    /** {@code REST}, {@code MQTT} or {@code SIMULATOR}. */
    @Column(name = "source", nullable = false, length = 16)
    private String source = "REST";

    @Column(name = "validation_status", nullable = false, length = 16)
    private String validationStatus = VALID;

    @Column(name = "validation_error", length = 255)
    private String validationError;

    /** Kept from 008; {@code SYNCED} for everything taken in. */
    @Column(name = "sync_status", length = 32)
    private String syncStatus = "SYNCED";

    public VitalSigns() {
    }

    public boolean isValid() {
        return VALID.equals(validationStatus);
    }

    public Long getId() {
        return id;
    }

    public Customer getCustomer() {
        return customer;
    }

    public void setCustomer(Customer customer) {
        this.customer = customer;
    }

    public MedicalDevice getDevice() {
        return device;
    }

    public void setDevice(MedicalDevice device) {
        this.device = device;
    }

    public DeviceAssignment getDeviceAssignment() {
        return deviceAssignment;
    }

    public void setDeviceAssignment(DeviceAssignment deviceAssignment) {
        this.deviceAssignment = deviceAssignment;
    }

    public MeasurementSession getSession() {
        return session;
    }

    public void setSession(MeasurementSession session) {
        this.session = session;
    }

    public Integer getHeartRate() {
        return heartRate;
    }

    public void setHeartRate(Integer heartRate) {
        this.heartRate = heartRate;
    }

    public BigDecimal getSpo2() {
        return spo2;
    }

    public void setSpo2(BigDecimal spo2) {
        this.spo2 = spo2;
    }

    public BigDecimal getTemperature() {
        return temperature;
    }

    public void setTemperature(BigDecimal temperature) {
        this.temperature = temperature;
    }

    public OffsetDateTime getMeasuredAt() {
        return measuredAt;
    }

    public void setMeasuredAt(OffsetDateTime measuredAt) {
        this.measuredAt = measuredAt;
    }

    public OffsetDateTime getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(OffsetDateTime receivedAt) {
        this.receivedAt = receivedAt;
    }

    public String getClientRecordId() {
        return clientRecordId;
    }

    public void setClientRecordId(String clientRecordId) {
        this.clientRecordId = clientRecordId;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getValidationStatus() {
        return validationStatus;
    }

    public String getValidationError() {
        return validationError;
    }

    public void markInvalid(String reason) {
        this.validationStatus = INVALID;
        this.validationError = reason == null || reason.length() <= 255 ? reason : reason.substring(0, 255);
    }
}
