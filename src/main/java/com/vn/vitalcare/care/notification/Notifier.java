package com.vn.vitalcare.care.notification;

import com.vn.vitalcare.entity.Notification;
import com.vn.vitalcare.entity.NotificationLevel;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tells one account something (FE-13), the one way every feature does it: a
 * row first — what somebody offline sees on their next sign-in — then, once
 * that row is committed, the same notification pushed to the account's own
 * {@code /user/queue/notifications}. A notification rolled back with its
 * transaction is never pushed.
 * ponytail: single-node in-memory broker, as the other publishers.
 */
@Service
@Transactional(readOnly = true)
public class Notifier {

    /** Minus the {@code /user} prefix Spring adds per session. */
    public static final String DESTINATION = "/queue/notifications";
    private static final int RECENT = 20;

    /** Committed: push it. */
    public record Raised(Long userId, NotificationResponse notification) {
    }

    private final NotificationRepository notifications;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public Notifier(NotificationRepository notifications, ApplicationEventPublisher events, Clock clock) {
        this.notifications = notifications;
        this.events = events;
        this.clock = clock;
    }

    /** Notifies an account; nothing when there is no account to tell (a relative without a sign-in). */
    @Transactional
    public Notification notify(Long userId, NotificationLevel level, String type, String title, String body,
                               String link) {
        if (userId == null) {
            return null;
        }
        // Built from names up to 255 long ("Đã có kết quả " + a service's): clipped so a
        // notification can never fail the action it reports on.
        Notification saved = notifications.save(new Notification(userId, level, type, clip(title, 255),
                clip(body, 2000), clip(link, 500), OffsetDateTime.now(clock)));
        events.publishEvent(new Raised(userId, NotificationResponse.from(saved)));
        return saved;
    }

    private static String clip(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    // ---- the account's own -------------------------------------------------------------

    /** A page by offset, as simple-rest asks ({@code _start}/{@code _end}); at most 100 at a time. */
    public Page<Notification> own(Long userId, boolean unreadOnly, int start, int end) {
        int size = Math.min(Math.max(end - start, 1), 100);
        return notifications.findOwn(userId, unreadOnly, PageRequest.of(Math.max(start, 0) / size, size));
    }

    public List<Notification> recent(Long userId) {
        return notifications.findRecent(userId, PageRequest.of(0, RECENT));
    }

    public long unread(Long userId) {
        return notifications.countUnread(userId);
    }

    public Notification get(Long userId, Long id) {
        return notifications.findOwn(id, userId).orElseThrow(() -> new ResourceNotFoundException("Notification", id));
    }

    @Transactional
    public Notification markRead(Long userId, Long id) {
        Notification notification = get(userId, id);
        notification.markRead(OffsetDateTime.now(clock));
        return notifications.save(notification);
    }

    @Transactional
    public int markAllRead(Long userId) {
        return notifications.markAllRead(userId, OffsetDateTime.now(clock));
    }

    @Transactional
    public void remove(Long userId, Long id) {
        Notification notification = get(userId, id);
        notification.markDeleted(userId, clock.instant());
        notifications.save(notification);
    }
}
