package com.vn.vitalcare.entity;

import com.vn.vitalcare.share.data.BaseEntity;
import com.vn.vitalcare.share.security.rowlevel.RowLevelResource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * When a patient comes back for their results (BF-07 Result Return Schedule):
 * {@code SCHEDULED}, then {@code ARRIVED} once the desk verifies them, or
 * {@code MISSED} once the day passed without them — rescheduling puts it back
 * to {@code SCHEDULED} at a new time.
 */
@Entity
@RowLevelResource("result_returns")
@Table(name = "result_return")
public class ResultReturn extends BaseEntity {

    public static final String SCHEDULED = "SCHEDULED";
    public static final String ARRIVED = "ARRIVED";
    public static final String MISSED = "MISSED";
    public static final String CANCELLED = "CANCELLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "appointment_id", nullable = false)
    private Appointment appointment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @Column(name = "clinic_id", nullable = false)
    private UUID clinicId;

    @Column(name = "scheduled_at", nullable = false)
    private OffsetDateTime scheduledAt;

    @Column(name = "note")
    private String note;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "arrived_at")
    private OffsetDateTime arrivedAt;

    @Column(name = "verified_by_id")
    private Long verifiedById;

    @Column(name = "missed_at")
    private OffsetDateTime missedAt;

    public ResultReturn() {
    }

    public Long getId() {
        return id;
    }

    public Appointment getAppointment() {
        return appointment;
    }

    public void setAppointment(Appointment appointment) {
        this.appointment = appointment;
    }

    public Customer getCustomer() {
        return customer;
    }

    public void setCustomer(Customer customer) {
        this.customer = customer;
    }

    public UUID getClinicId() {
        return clinicId;
    }

    public void setClinicId(UUID clinicId) {
        this.clinicId = clinicId;
    }

    public OffsetDateTime getScheduledAt() {
        return scheduledAt;
    }

    public void setScheduledAt(OffsetDateTime scheduledAt) {
        this.scheduledAt = scheduledAt;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public OffsetDateTime getArrivedAt() {
        return arrivedAt;
    }

    public void setArrivedAt(OffsetDateTime arrivedAt) {
        this.arrivedAt = arrivedAt;
    }

    public Long getVerifiedById() {
        return verifiedById;
    }

    public void setVerifiedById(Long verifiedById) {
        this.verifiedById = verifiedById;
    }

    public OffsetDateTime getMissedAt() {
        return missedAt;
    }

    public void setMissedAt(OffsetDateTime missedAt) {
        this.missedAt = missedAt;
    }
}
