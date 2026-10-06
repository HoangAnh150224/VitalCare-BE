package com.vn.vitalcare.entity;

import com.vn.vitalcare.share.data.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(name = "monitoring_assignment")
public class MonitoringAssignment extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id")
    private Employee employee;

    @Column(name = "assigned_at")
    private OffsetDateTime assignedAt;

    @Column(name = "unassigned_at")
    private OffsetDateTime unassignedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private AssignmentStatus status = AssignmentStatus.ACTIVE;

    @Column(name = "note", columnDefinition = "text")
    private String note;

    // Bare users.id, like the audit columns -- see BaseEntity.
    @Column(name = "assigned_by_id")
    private Long assignedById;

    @Column(name = "ended_by_id")
    private Long endedById;

    /** Opens the assignment: who, by whom, when. */
    public void start(Customer customer, Employee employee, Long actorId, OffsetDateTime when) {
        this.customer = customer;
        this.employee = employee;
        this.assignedById = actorId;
        this.assignedAt = when;
        this.status = AssignmentStatus.ACTIVE;
    }

    /** Takes the person off the patient's care team. The row stays as the history. */
    public void end(Long actorId, OffsetDateTime when) {
        this.status = AssignmentStatus.ENDED;
        this.endedById = actorId;
        this.unassignedAt = when;
    }

    public boolean isActive() {
        return status == AssignmentStatus.ACTIVE;
    }

    public Long getAssignedById() {
        return assignedById;
    }

    public Long getEndedById() {
        return endedById;
    }

    public MonitoringAssignment() {
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

    public Employee getEmployee() {
        return employee;
    }

    public void setEmployee(Employee employee) {
        this.employee = employee;
    }

    public OffsetDateTime getAssignedAt() {
        return assignedAt;
    }

    public void setAssignedAt(OffsetDateTime assignedAt) {
        this.assignedAt = assignedAt;
    }

    public OffsetDateTime getUnassignedAt() {
        return unassignedAt;
    }

    public void setUnassignedAt(OffsetDateTime unassignedAt) {
        this.unassignedAt = unassignedAt;
    }

    public AssignmentStatus getStatus() {
        return status;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
