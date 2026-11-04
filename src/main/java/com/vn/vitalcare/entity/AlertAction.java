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
 * One step in an alert's Action History (BF-05): a reading that raised or fed
 * it, a change of severity, an acknowledgement, a note, the resolution or the
 * dismissal, a notification or an escalation. Appended, never changed.
 */
@Entity
@Table(name = "alert_action")
public class AlertAction extends BaseEntity {

    public static final String TRIGGERED = "TRIGGERED";
    public static final String REPEATED = "REPEATED";
    public static final String SEVERITY_RAISED = "SEVERITY_RAISED";
    public static final String ACKNOWLEDGED = "ACKNOWLEDGED";
    public static final String NOTE = "NOTE";
    public static final String RESOLVED = "RESOLVED";
    public static final String DISMISSED = "DISMISSED";
    public static final String NOTIFIED = "NOTIFIED";
    public static final String ESCALATED = "ESCALATED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "alert_id", nullable = false)
    private Alert alert;

    @Column(name = "action", nullable = false, length = 32)
    private String action;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vital_sign_id")
    private VitalSigns vitalSign;

    @Column(name = "value", precision = 6, scale = 2)
    private BigDecimal value;

    @Column(name = "severity", length = 16)
    private String severity;

    @Column(name = "actor_id")
    private Long actorId;

    @Column(name = "actor_name")
    private String actorName;

    @Column(name = "note")
    private String note;

    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;

    protected AlertAction() {
    }

    public AlertAction(Alert alert, String action, OffsetDateTime when) {
        this.alert = alert;
        this.action = action;
        this.occurredAt = when;
        this.severity = alert.getSeverity();
    }

    public AlertAction reading(VitalSigns reading, BigDecimal value) {
        this.vitalSign = reading;
        this.value = value;
        return this;
    }

    public AlertAction by(Long actorId, String actorName) {
        this.actorId = actorId;
        this.actorName = actorName;
        return this;
    }

    public AlertAction note(String note) {
        this.note = note;
        return this;
    }

    public Long getId() {
        return id;
    }

    public Alert getAlert() {
        return alert;
    }

    public String getAction() {
        return action;
    }

    public VitalSigns getVitalSign() {
        return vitalSign;
    }

    public BigDecimal getValue() {
        return value;
    }

    public String getSeverity() {
        return severity;
    }

    public Long getActorId() {
        return actorId;
    }

    public String getActorName() {
        return actorName;
    }

    public String getNote() {
        return note;
    }

    public OffsetDateTime getOccurredAt() {
        return occurredAt;
    }
}
