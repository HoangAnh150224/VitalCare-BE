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
 * A patient's grant to a caregiver's account (BF-06): which parts of their
 * health data, until when, and the number to call in an emergency. Ends by
 * being revoked or by passing its expiry; a patient has at most two in force.
 */
@Entity
@Table(name = "sharing_permission")
public class SharingPermission extends BaseEntity {

    public static final String ACTIVE = "ACTIVE";
    public static final String REVOKED = "REVOKED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "caregiver_user_id", nullable = false)
    private com.vn.vitalcare.identity.user.entity.User caregiver;

    @Column(name = "share_vitals", nullable = false)
    private boolean shareVitals;

    @Column(name = "share_alerts", nullable = false)
    private boolean shareAlerts;

    @Column(name = "share_appointments", nullable = false)
    private boolean shareAppointments;

    @Column(name = "emergency_phone", nullable = false)
    private String emergencyPhone;

    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;

    @Column(name = "note")
    private String note;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    @Column(name = "revoked_by_id")
    private Long revokedById;

    public SharingPermission() {
    }

    /** In force at {@code now}: not revoked and not past its expiry. */
    public boolean inForce(OffsetDateTime now) {
        return ACTIVE.equals(status) && (expiresAt == null || expiresAt.isAfter(now));
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

    public com.vn.vitalcare.identity.user.entity.User getCaregiver() {
        return caregiver;
    }

    public void setCaregiver(com.vn.vitalcare.identity.user.entity.User caregiver) {
        this.caregiver = caregiver;
    }

    public boolean isShareVitals() {
        return shareVitals;
    }

    public void setShareVitals(boolean shareVitals) {
        this.shareVitals = shareVitals;
    }

    public boolean isShareAlerts() {
        return shareAlerts;
    }

    public void setShareAlerts(boolean shareAlerts) {
        this.shareAlerts = shareAlerts;
    }

    public boolean isShareAppointments() {
        return shareAppointments;
    }

    public void setShareAppointments(boolean shareAppointments) {
        this.shareAppointments = shareAppointments;
    }

    public String getEmergencyPhone() {
        return emergencyPhone;
    }

    public void setEmergencyPhone(String emergencyPhone) {
        this.emergencyPhone = emergencyPhone;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(OffsetDateTime expiresAt) {
        this.expiresAt = expiresAt;
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

    public OffsetDateTime getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(OffsetDateTime revokedAt) {
        this.revokedAt = revokedAt;
    }

    public Long getRevokedById() {
        return revokedById;
    }

    public void setRevokedById(Long revokedById) {
        this.revokedById = revokedById;
    }
}
