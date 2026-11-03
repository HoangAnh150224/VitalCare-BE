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
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A doctor's order for IoMT monitoring and the patient's answer to it (BF-04):
 * {@code ACCEPTED} makes it {@code ACTIVE} — the only state under which a
 * device may be handed to the patient — until it is {@code ENDED};
 * {@code DECLINED} is kept as the record that the patient said no.
 */
@Entity
@RowLevelResource("monitoring_orders")
@Table(name = "monitoring_order")
public class MonitoringOrder extends BaseEntity {

    public static final String ACCEPTED = "ACCEPTED";
    public static final String DECLINED = "DECLINED";
    public static final String ACTIVE = "ACTIVE";
    public static final String ENDED = "ENDED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "encounter_id")
    private ClinicalEncounter encounter;

    @Column(name = "clinic_id", nullable = false)
    private UUID clinicId;

    @Column(name = "ordered_by_id")
    private Long orderedById;

    @Column(name = "ordered_by_name")
    private String orderedByName;

    @Column(name = "reason", nullable = false)
    private String reason;

    @Column(name = "decision", nullable = false)
    private String decision;

    @Column(name = "consent_note")
    private String consentNote;

    @Column(name = "decided_at", nullable = false)
    private OffsetDateTime decidedAt;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "ended_at")
    private OffsetDateTime endedAt;

    @Column(name = "ended_by_id")
    private Long endedById;

    @Column(name = "end_note")
    private String endNote;

    public MonitoringOrder() {
    }

    public boolean isActive() {
        return ACTIVE.equals(status);
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

    public ClinicalEncounter getEncounter() {
        return encounter;
    }

    public void setEncounter(ClinicalEncounter encounter) {
        this.encounter = encounter;
    }

    public UUID getClinicId() {
        return clinicId;
    }

    public void setClinicId(UUID clinicId) {
        this.clinicId = clinicId;
    }

    public Long getOrderedById() {
        return orderedById;
    }

    public void setOrderedById(Long orderedById) {
        this.orderedById = orderedById;
    }

    public String getOrderedByName() {
        return orderedByName;
    }

    public void setOrderedByName(String orderedByName) {
        this.orderedByName = orderedByName;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getDecision() {
        return decision;
    }

    public void setDecision(String decision) {
        this.decision = decision;
    }

    public String getConsentNote() {
        return consentNote;
    }

    public void setConsentNote(String consentNote) {
        this.consentNote = consentNote;
    }

    public OffsetDateTime getDecidedAt() {
        return decidedAt;
    }

    public void setDecidedAt(OffsetDateTime decidedAt) {
        this.decidedAt = decidedAt;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public OffsetDateTime getEndedAt() {
        return endedAt;
    }

    public void setEndedAt(OffsetDateTime endedAt) {
        this.endedAt = endedAt;
    }

    public Long getEndedById() {
        return endedById;
    }

    public void setEndedById(Long endedById) {
        this.endedById = endedById;
    }

    public String getEndNote() {
        return endNote;
    }

    public void setEndNote(String endNote) {
        this.endNote = endNote;
    }
}
