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
 * A member of staff's membership of a clinic (SRS 3.18.9): at most one
 * {@code ACTIVE} at a time — the clinic {@link Employee#getClinic()} names —
 * and every earlier one {@code ENDED}, with when and why.
 */
@Entity
@Table(name = "staff_membership")
public class StaffMembership extends BaseEntity {

    public static final String ACTIVE = "ACTIVE";
    public static final String ENDED = "ENDED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "clinic_id", nullable = false)
    private Clinic clinic;

    @Column(name = "status", nullable = false, length = 16)
    private String status = ACTIVE;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    @Column(name = "ended_at")
    private OffsetDateTime endedAt;

    @Column(name = "end_reason", length = 500)
    private String endReason;

    protected StaffMembership() {
    }

    public StaffMembership(Employee employee, Clinic clinic, OffsetDateTime at) {
        this.employee = employee;
        this.clinic = clinic;
        this.startedAt = at;
    }

    public void end(OffsetDateTime at, String reason) {
        this.status = ENDED;
        this.endedAt = at;
        this.endReason = reason;
    }

    public Long getId() {
        return id;
    }

    public Employee getEmployee() {
        return employee;
    }

    public Clinic getClinic() {
        return clinic;
    }

    public String getStatus() {
        return status;
    }

    public OffsetDateTime getStartedAt() {
        return startedAt;
    }

    public OffsetDateTime getEndedAt() {
        return endedAt;
    }

    public String getEndReason() {
        return endReason;
    }
}
