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
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * An abnormal vital sign that needs somebody (BF-05): raised by a rule, then
 * {@code OPEN} → {@code ACKNOWLEDGED} → {@code RESOLVED} (with handling notes)
 * or {@code DISMISSED} (with a reason). While it is open, further abnormal
 * readings of the same measure for the same patient add to it rather than
 * raising another; a worse one raises its severity.
 */
@Entity
@RowLevelResource("alerts")
@Table(name = "alert")
public class Alert extends BaseEntity {

    public static final String OPEN = "OPEN";
    public static final String ACKNOWLEDGED = "ACKNOWLEDGED";
    public static final String RESOLVED = "RESOLVED";
    public static final String DISMISSED = "DISMISSED";
    public static final String WARNING = "WARNING";
    public static final String CRITICAL = "CRITICAL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** The reading that raised it. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vital_sign_id")
    private VitalSigns vitalSign;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @Column(name = "clinic_id")
    private UUID clinicId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "device_id")
    private MedicalDevice device;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rule_id")
    private AlertRule rule;

    /** Kept from 008: {@code VITAL_SIGN}. */
    @Column(name = "alert_type", length = 64)
    private String alertType = "VITAL_SIGN";

    @Column(name = "severity", length = 32)
    private String severity;

    @Column(name = "message")
    private String message;

    @Column(name = "status", length = 32)
    private String status = OPEN;

    @Column(name = "metric", length = 32)
    private String metric;

    @Column(name = "operator", length = 8)
    private String operator;

    @Column(name = "threshold", precision = 6, scale = 2)
    private BigDecimal threshold;

    @Column(name = "measured_value", precision = 6, scale = 2)
    private BigDecimal measuredValue;

    @Column(name = "last_value", precision = 6, scale = 2)
    private BigDecimal lastValue;

    @Column(name = "triggered_at")
    private OffsetDateTime triggeredAt;

    @Column(name = "last_triggered_at")
    private OffsetDateTime lastTriggeredAt;

    @Column(name = "occurrences", nullable = false)
    private int occurrences = 1;

    @Column(name = "acknowledged_at")
    private OffsetDateTime acknowledgedAt;

    @Column(name = "acknowledged_by_id")
    private Long acknowledgedById;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    @Column(name = "resolved_by_id")
    private Long resolvedById;

    @Column(name = "dismissed_at")
    private OffsetDateTime dismissedAt;

    @Column(name = "dismissed_by_id")
    private Long dismissedById;

    @Column(name = "handling_notes")
    private String handlingNotes;

    @Column(name = "dismiss_reason")
    private String dismissReason;

    public Alert() {
    }

    public boolean isActive() {
        return OPEN.equals(status) || ACKNOWLEDGED.equals(status);
    }

    public static int rank(String severity) {
        return CRITICAL.equals(severity) ? 2 : WARNING.equals(severity) ? 1 : 0;
    }

    public void raise(VitalSigns reading, AlertRule rule, BigDecimal value, String message, OffsetDateTime when) {
        this.vitalSign = reading;
        this.customer = reading.getCustomer();
        this.device = reading.getDevice();
        this.clinicId = reading.getDevice() == null ? rule.getClinicId() : reading.getDevice().getClinicId();
        this.rule = rule;
        this.metric = rule.getMetric();
        this.operator = rule.getOperator();
        this.threshold = rule.getThreshold();
        this.severity = rule.getSeverity();
        this.measuredValue = value;
        this.lastValue = value;
        this.message = message;
        this.triggeredAt = when;
        this.lastTriggeredAt = when;
    }

    /** Another abnormal reading while it is open; true when it made it worse (a higher severity). */
    public boolean repeat(AlertRule rule, BigDecimal value, String message, OffsetDateTime when) {
        occurrences++;
        lastValue = value;
        lastTriggeredAt = when;
        if (rank(rule.getSeverity()) > rank(severity)) {
            this.severity = rule.getSeverity();
            this.rule = rule;
            this.operator = rule.getOperator();
            this.threshold = rule.getThreshold();
            this.message = message;
            return true;
        }
        return false;
    }

    public void acknowledge(Long by, OffsetDateTime when) {
        status = ACKNOWLEDGED;
        acknowledgedById = by;
        acknowledgedAt = when;
    }

    public void resolve(Long by, String notes, OffsetDateTime when) {
        if (acknowledgedAt == null) {
            acknowledge(by, when);
        }
        status = RESOLVED;
        resolvedById = by;
        resolvedAt = when;
        handlingNotes = notes;
    }

    public void dismiss(Long by, String reason, OffsetDateTime when) {
        status = DISMISSED;
        dismissedById = by;
        dismissedAt = when;
        dismissReason = reason;
    }

    public Long getId() {
        return id;
    }

    public VitalSigns getVitalSign() {
        return vitalSign;
    }

    public void setVitalSign(VitalSigns vitalSign) {
        this.vitalSign = vitalSign;
    }

    public Customer getCustomer() {
        return customer;
    }

    public UUID getClinicId() {
        return clinicId;
    }

    public MedicalDevice getDevice() {
        return device;
    }

    public AlertRule getRule() {
        return rule;
    }

    public String getAlertType() {
        return alertType;
    }

    public String getSeverity() {
        return severity;
    }

    public String getMessage() {
        return message;
    }

    public String getStatus() {
        return status;
    }

    public String getMetric() {
        return metric;
    }

    public String getOperator() {
        return operator;
    }

    public BigDecimal getThreshold() {
        return threshold;
    }

    public BigDecimal getMeasuredValue() {
        return measuredValue;
    }

    public BigDecimal getLastValue() {
        return lastValue;
    }

    public OffsetDateTime getTriggeredAt() {
        return triggeredAt;
    }

    public OffsetDateTime getLastTriggeredAt() {
        return lastTriggeredAt;
    }

    public int getOccurrences() {
        return occurrences;
    }

    public OffsetDateTime getAcknowledgedAt() {
        return acknowledgedAt;
    }

    public Long getAcknowledgedById() {
        return acknowledgedById;
    }

    public OffsetDateTime getResolvedAt() {
        return resolvedAt;
    }

    public Long getResolvedById() {
        return resolvedById;
    }

    public OffsetDateTime getDismissedAt() {
        return dismissedAt;
    }

    public Long getDismissedById() {
        return dismissedById;
    }

    public String getHandlingNotes() {
        return handlingNotes;
    }

    public String getDismissReason() {
        return dismissReason;
    }
}
