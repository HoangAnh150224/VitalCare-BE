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

/** One line of the Caregiver Escalation and Call Log: a call placed or an SMS sent, and what came of it. */
@Entity
@Table(name = "escalation_call")
public class EscalationCall extends BaseEntity {

    public static final String CALL = "CALL";
    public static final String SMS = "SMS";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "notice_id", nullable = false)
    private EmergencyNotice notice;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "alert_id", nullable = false)
    private Alert alert;

    @Column(name = "channel", nullable = false)
    private String channel;

    @Column(name = "phone", nullable = false)
    private String phone;

    @Column(name = "outcome", nullable = false)
    private String outcome;

    @Column(name = "detail")
    private String detail;

    @Column(name = "placed_at", nullable = false)
    private OffsetDateTime placedAt;

    public EscalationCall() {
    }


    public Long getId() {
        return id;
    }

    public EmergencyNotice getNotice() {
        return notice;
    }

    public void setNotice(EmergencyNotice notice) {
        this.notice = notice;
    }

    public Alert getAlert() {
        return alert;
    }

    public void setAlert(Alert alert) {
        this.alert = alert;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getOutcome() {
        return outcome;
    }

    public void setOutcome(String outcome) {
        this.outcome = outcome;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public OffsetDateTime getPlacedAt() {
        return placedAt;
    }

    public void setPlacedAt(OffsetDateTime placedAt) {
        this.placedAt = placedAt;
    }
}
