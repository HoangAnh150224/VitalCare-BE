package com.vn.vitalcare.entity;

import com.vn.vitalcare.share.data.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * Something one account is told (FE-13): a visit registered, the fee
 * received, called into a room, a result in, a reply from the clinic, an
 * alert. Kept as a row so somebody offline sees it on their next sign-in;
 * pushed live as well over {@code /user/queue/notifications}.
 *
 * <p>The account is a bare {@code users.id}, for the reason {@code BaseEntity}
 * gives about entities of the identity domain.
 */
@Entity
@Table(name = "notification")
public class Notification extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** The alert it is about, when it is one (FE-19). */
    @Column(name = "alert_id")
    private Long alertId;

    /** What happened, for grouping and filtering: {@code appointment}, {@code payment}, {@code chat}, {@code alert}... */
    @Column(name = "type", length = 64)
    private String type;

    @Enumerated(EnumType.STRING)
    @Column(name = "level", nullable = false, length = 16)
    private NotificationLevel level = NotificationLevel.INFO;

    @Column(name = "title", length = 255)
    private String title;

    @Column(name = "content", columnDefinition = "text")
    private String content;

    /** Where in the app it leads; a path, never a full URL. */
    @Column(name = "link", length = 255)
    private String link;

    /** {@code SENT} once pushed; kept for the delivery status the SRS asks to show. */
    @Column(name = "status", length = 32)
    private String status;

    @Column(name = "sent_at")
    private OffsetDateTime sentAt;

    @Column(name = "read_at")
    private OffsetDateTime readAt;

    public Notification() {
    }

    public Notification(Long userId, NotificationLevel level, String type, String title, String content, String link,
                        OffsetDateTime sentAt) {
        this.userId = userId;
        this.level = level;
        this.type = type;
        this.title = title;
        this.content = content;
        this.link = link;
        this.status = "SENT";
        this.sentAt = sentAt;
    }

    public void markRead(OffsetDateTime when) {
        if (readAt == null) {
            readAt = when;
        }
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getAlertId() {
        return alertId;
    }

    public void setAlertId(Long alertId) {
        this.alertId = alertId;
    }

    public String getType() {
        return type;
    }

    public NotificationLevel getLevel() {
        return level;
    }

    public String getTitle() {
        return title;
    }

    public String getContent() {
        return content;
    }

    public String getLink() {
        return link;
    }

    public String getStatus() {
        return status;
    }

    public OffsetDateTime getSentAt() {
        return sentAt;
    }

    public OffsetDateTime getReadAt() {
        return readAt;
    }
}
