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
import java.util.UUID;

/**
 * A critical alert told to one caregiver (BF-06): {@code PENDING} until they
 * acknowledge it, or {@code ESCALATED} once the escalation delay passed without
 * that — a call and an SMS to the emergency number, each in the call log.
 */
@Entity
@Table(name = "emergency_notice")
public class EmergencyNotice extends BaseEntity {

    public static final String PENDING = "PENDING";
    public static final String ACKNOWLEDGED = "ACKNOWLEDGED";
    public static final String ESCALATED = "ESCALATED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "alert_id", nullable = false)
    private Alert alert;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sharing_permission_id")
    private SharingPermission permission;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipient_user_id", nullable = false)
    private com.vn.vitalcare.identity.user.entity.User recipient;

    @Column(name = "emergency_phone")
    private String emergencyPhone;

    @Column(name = "notified_at", nullable = false)
    private OffsetDateTime notifiedAt;

    @Column(name = "acknowledged_at")
    private OffsetDateTime acknowledgedAt;

    @Column(name = "escalated_at")
    private OffsetDateTime escalatedAt;

    @Column(name = "status", nullable = false)
    private String status;

    public EmergencyNotice() {
    }


    public Long getId() {
        return id;
    }

    public Alert getAlert() {
        return alert;
    }

    public void setAlert(Alert alert) {
        this.alert = alert;
    }

    public SharingPermission getPermission() {
        return permission;
    }

    public void setPermission(SharingPermission permission) {
        this.permission = permission;
    }

    public Customer getCustomer() {
        return customer;
    }

    public void setCustomer(Customer customer) {
        this.customer = customer;
    }

    public com.vn.vitalcare.identity.user.entity.User getRecipient() {
        return recipient;
    }

    public void setRecipient(com.vn.vitalcare.identity.user.entity.User recipient) {
        this.recipient = recipient;
    }

    public String getEmergencyPhone() {
        return emergencyPhone;
    }

    public void setEmergencyPhone(String emergencyPhone) {
        this.emergencyPhone = emergencyPhone;
    }

    public OffsetDateTime getNotifiedAt() {
        return notifiedAt;
    }

    public void setNotifiedAt(OffsetDateTime notifiedAt) {
        this.notifiedAt = notifiedAt;
    }

    public OffsetDateTime getAcknowledgedAt() {
        return acknowledgedAt;
    }

    public void setAcknowledgedAt(OffsetDateTime acknowledgedAt) {
        this.acknowledgedAt = acknowledgedAt;
    }

    public OffsetDateTime getEscalatedAt() {
        return escalatedAt;
    }

    public void setEscalatedAt(OffsetDateTime escalatedAt) {
        this.escalatedAt = escalatedAt;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
