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
import java.time.OffsetDateTime;

/**
 * One sitting with a device (FE-03 Vital Sign Measurement): started on the
 * phone, readings taken, then completed or cancelled. Known to the server by
 * the phone's own id for it, so an upload that is retried finds the same one.
 */
@Entity
@Table(name = "measurement_session")
public class MeasurementSession extends BaseEntity {

    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String COMPLETED = "COMPLETED";
    public static final String CANCELLED = "CANCELLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "device_id", nullable = false)
    private MedicalDevice device;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "device_assignment_id")
    private DeviceAssignment deviceAssignment;

    @Column(name = "client_session_id", nullable = false, length = 64)
    private String clientSessionId;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    @Column(name = "ended_at")
    private OffsetDateTime endedAt;

    @Column(name = "status", nullable = false, length = 16)
    private String status = IN_PROGRESS;

    protected MeasurementSession() {
    }

    public MeasurementSession(MedicalDevice device, Customer customer, DeviceAssignment assignment,
                              String clientSessionId, OffsetDateTime startedAt) {
        this.device = device;
        this.customer = customer;
        this.deviceAssignment = assignment;
        this.clientSessionId = clientSessionId;
        this.startedAt = startedAt;
    }

    public void end(String status, OffsetDateTime when) {
        this.status = status;
        this.endedAt = when;
    }

    /** A reading earlier than the session's start widens it: the phone's clock and the device's differ. */
    public void covers(OffsetDateTime measuredAt) {
        if (measuredAt != null && measuredAt.isBefore(startedAt)) {
            startedAt = measuredAt;
        }
    }

    public Long getId() {
        return id;
    }

    public MedicalDevice getDevice() {
        return device;
    }

    public Customer getCustomer() {
        return customer;
    }

    public DeviceAssignment getDeviceAssignment() {
        return deviceAssignment;
    }

    public String getClientSessionId() {
        return clientSessionId;
    }

    public OffsetDateTime getStartedAt() {
        return startedAt;
    }

    public OffsetDateTime getEndedAt() {
        return endedAt;
    }

    public String getStatus() {
        return status;
    }
}
