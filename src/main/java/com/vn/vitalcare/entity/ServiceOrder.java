package com.vn.vitalcare.entity;

import com.vn.vitalcare.share.data.BaseEntity;
import com.vn.vitalcare.share.security.rowlevel.RowLevelResource;
import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A service a doctor ordered during an encounter (Report 1 FE-22), and its
 * result: ordered, done by staff of the clinic, its result recorded with any
 * files, then seen by the doctor before they conclude.
 *
 * <p>The price is copied in when ordered; the bill follows it, not a later
 * change to the catalogue.
 */
@Entity
@RowLevelResource("service_orders")
@Table(name = "service_order")
public class ServiceOrder extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "encounter_id", nullable = false)
    private ClinicalEncounter encounter;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "clinic_id", nullable = false)
    private Clinic clinic;

    /** The same column as {@link #clinic}, for row-level policies; see {@code Appointment.clinicId}. */
    @Column(name = "clinic_id", insertable = false, updatable = false)
    private UUID clinicId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "service_id", nullable = false)
    private MedicalService service;

    @Column(name = "unit_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitPrice;

    /** What the doctor wants done or looked at, for whoever performs it. */
    @Column(name = "instruction", length = 1000)
    private String instruction;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ServiceOrderStatus status = ServiceOrderStatus.ORDERED;

    @Column(name = "expected_result_at")
    private OffsetDateTime expectedResultAt;

    // Bare users.id, like the audit columns.
    @Column(name = "performed_by_id")
    private Long performedById;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "result_text", columnDefinition = "text")
    private String resultText;

    @Column(name = "result_at")
    private OffsetDateTime resultAt;

    @Column(name = "reviewed_by_id")
    private Long reviewedById;

    @Column(name = "reviewed_at")
    private OffsetDateTime reviewedAt;

    @Column(name = "cancelled_at")
    private OffsetDateTime cancelledAt;

    @Column(name = "cancel_reason", length = 500)
    private String cancelReason;

    @OneToMany(mappedBy = "order", cascade = CascadeType.PERSIST)
    @OrderBy("createdAt asc")
    private List<ServiceOrderFile> files = new ArrayList<>();

    public ServiceOrder() {
    }

    /** Somebody has started on it. Only from {@link ServiceOrderStatus#ORDERED}; the service checks. */
    public void start(Long userId, OffsetDateTime when) {
        this.status = ServiceOrderStatus.IN_PROGRESS;
        this.performedById = userId;
        this.startedAt = when;
    }

    /** The result is in. From {@link ServiceOrderStatus#ORDERED} or {@link ServiceOrderStatus#IN_PROGRESS}. */
    public void recordResult(Long userId, OffsetDateTime when) {
        if (this.startedAt == null) {
            this.startedAt = when;
        }
        if (this.performedById == null) {
            this.performedById = userId;
        }
        this.status = ServiceOrderStatus.RESULT_READY;
        this.resultAt = when;
    }

    /** The doctor has read the result. Only from {@link ServiceOrderStatus#RESULT_READY}. */
    public void review(Long userId, OffsetDateTime when) {
        this.status = ServiceOrderStatus.REVIEWED;
        this.reviewedById = userId;
        this.reviewedAt = when;
    }

    /** Not done after all. Only before a result is in; the service checks. */
    public void cancel(OffsetDateTime when, String reason) {
        this.status = ServiceOrderStatus.CANCELLED;
        this.cancelledAt = when;
        this.cancelReason = reason;
    }

    /** Still to be done or read: what keeps the encounter from being concluded. */
    public boolean isOutstanding() {
        return status == ServiceOrderStatus.ORDERED
                || status == ServiceOrderStatus.IN_PROGRESS
                || status == ServiceOrderStatus.RESULT_READY;
    }

    public Long getId() {
        return id;
    }

    public ClinicalEncounter getEncounter() {
        return encounter;
    }

    public void setEncounter(ClinicalEncounter encounter) {
        this.encounter = encounter;
    }

    public Customer getCustomer() {
        return customer;
    }

    public void setCustomer(Customer customer) {
        this.customer = customer;
    }

    public Clinic getClinic() {
        return clinic;
    }

    public void setClinic(Clinic clinic) {
        this.clinic = clinic;
        this.clinicId = clinic == null ? null : clinic.getClinicId();
    }

    public UUID getClinicId() {
        return clinicId;
    }

    public MedicalService getService() {
        return service;
    }

    public void setService(MedicalService service) {
        this.service = service;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public void setUnitPrice(BigDecimal unitPrice) {
        this.unitPrice = unitPrice;
    }

    public String getInstruction() {
        return instruction;
    }

    public void setInstruction(String instruction) {
        this.instruction = instruction;
    }

    public ServiceOrderStatus getStatus() {
        return status;
    }

    public OffsetDateTime getExpectedResultAt() {
        return expectedResultAt;
    }

    public void setExpectedResultAt(OffsetDateTime expectedResultAt) {
        this.expectedResultAt = expectedResultAt;
    }

    public Long getPerformedById() {
        return performedById;
    }

    public OffsetDateTime getStartedAt() {
        return startedAt;
    }

    public String getResultText() {
        return resultText;
    }

    public void setResultText(String resultText) {
        this.resultText = resultText;
    }

    public OffsetDateTime getResultAt() {
        return resultAt;
    }

    public Long getReviewedById() {
        return reviewedById;
    }

    public OffsetDateTime getReviewedAt() {
        return reviewedAt;
    }

    public OffsetDateTime getCancelledAt() {
        return cancelledAt;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public List<ServiceOrderFile> getFiles() {
        return files;
    }
}
