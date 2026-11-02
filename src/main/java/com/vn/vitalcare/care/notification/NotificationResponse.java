package com.vn.vitalcare.care.notification;

import com.vn.vitalcare.entity.Notification;
import com.vn.vitalcare.entity.NotificationLevel;
import java.time.Instant;
import java.time.OffsetDateTime;

/** A notification as the bell and the Notification Center read it. */
public record NotificationResponse(
        Long id,
        String type,
        NotificationLevel level,
        String title,
        String body,
        String link,
        boolean read,
        OffsetDateTime readAt,
        /** {@code SENT} once pushed. */
        String status,
        OffsetDateTime sentAt,
        Long alertId,
        Instant createdAt) {

    static NotificationResponse from(Notification n) {
        return new NotificationResponse(n.getId(), n.getType(), n.getLevel(), n.getTitle(), n.getContent(), n.getLink(),
                n.getReadAt() != null, n.getReadAt(), n.getStatus(), n.getSentAt(), n.getAlertId(), n.getCreatedAt());
    }
}
