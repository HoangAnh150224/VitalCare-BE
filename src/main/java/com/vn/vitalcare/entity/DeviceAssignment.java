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

@Entity
@Table(name = "device_assignment")
public class DeviceAssignment extends BaseEntity {

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
    @JoinColumn(name = "employee_id")
    private Employee employee;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "clinical_encounter_id")
    private ClinicalEncounter clinicalEncounter;

    @Column(name = "assigned_at")
    private OffsetDateTime assignedAt;

    @Column(name = "expected_return_at")
    private OffsetDateTime expectedReturnAt;

    @Column(name = "unassigned_at")
    private OffsetDateTime unassignedAt;

    @Column(name = "status")
    private String status;

    @Column(name = "note", columnDefinition = "text")
    private String note;

    public DeviceAssignment() {
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

    public Employee getEmployee() {
        return employee;
    }

    public void setEmployee(Employee employee) {
        this.employee = employee;
    }

    public ClinicalEncounter getClinicalEncounter() {
        return clinicalEncounter;
    }

    public void setClinicalEncounter(ClinicalEncounter clinicalEncounter) {
        this.clinicalEncounter = clinicalEncounter;
    }

    public OffsetDateTime getAssignedAt() {
        return assignedAt;
    }

    public void setAssignedAt(OffsetDateTime assignedAt) {
        this.assignedAt = assignedAt;
    }

    public OffsetDateTime getExpectedReturnAt() {
        return expectedReturnAt;
    }

    public void setExpectedReturnAt(OffsetDateTime expectedReturnAt) {
        this.expectedReturnAt = expectedReturnAt;
    }

    public OffsetDateTime getUnassignedAt() {
        return unassignedAt;
    }

    public void setUnassignedAt(OffsetDateTime unassignedAt) {
        this.unassignedAt = unassignedAt;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
