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
import java.util.UUID;

/**
 * When a vital sign raises an alert (FE-05): a measure below or above a
 * threshold, at a severity. A clinic's rule covers all its patients; a rule
 * with a patient is that patient's own, and for its measure and direction it
 * takes the place of the clinic's (BR-21).
 */
@Entity
@RowLevelResource("alert_rules")
@Table(name = "alert_rule")
public class AlertRule extends BaseEntity {

    public static final String BELOW = "BELOW";
    public static final String ABOVE = "ABOVE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "clinic_id", nullable = false)
    private UUID clinicId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @Column(name = "name", nullable = false)
    private String name;

    /** {@code HEART_RATE}, {@code SPO2} or {@code SKIN_TEMPERATURE}. */
    @Column(name = "metric", nullable = false, length = 32)
    private String metric;

    @Column(name = "operator", nullable = false, length = 8)
    private String operator;

    @Column(name = "threshold", nullable = false, precision = 6, scale = 2)
    private BigDecimal threshold;

    /** {@code WARNING} or {@code CRITICAL}. */
    @Column(name = "severity", nullable = false, length = 16)
    private String severity;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "note", length = 1000)
    private String note;

    public AlertRule() {
    }

    /** Whether a value breaks this rule. */
    public boolean breachedBy(BigDecimal value) {
        if (value == null) {
            return false;
        }
        int c = value.compareTo(threshold);
        return BELOW.equals(operator) ? c < 0 : c > 0;
    }

    public boolean isPatientRule() {
        return customer != null;
    }

    public Long getId() {
        return id;
    }

    public UUID getClinicId() {
        return clinicId;
    }

    public void setClinicId(UUID clinicId) {
        this.clinicId = clinicId;
    }

    public Customer getCustomer() {
        return customer;
    }

    public void setCustomer(Customer customer) {
        this.customer = customer;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getMetric() {
        return metric;
    }

    public void setMetric(String metric) {
        this.metric = metric;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public BigDecimal getThreshold() {
        return threshold;
    }

    public void setThreshold(BigDecimal threshold) {
        this.threshold = threshold;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
