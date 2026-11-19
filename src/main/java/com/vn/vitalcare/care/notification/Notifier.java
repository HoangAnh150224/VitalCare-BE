package com.vn.vitalcare.care.notification;

import com.vn.vitalcare.entity.Notification;
import com.vn.vitalcare.entity.NotificationLevel;
import com.vn.vitalcare.share.data.BaseEntitySpecifications;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import com.vn.vitalcare.share.web.ListParams;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
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
    private static final Set<String> SORTABLE = Set.of("level", "title", "createdAt");
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

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

    /** A page of the Notification Center: simple-rest paging, sorting, the quick filter and the advanced filter. */
    public Page<Notification> own(Long userId, ListParams params) {
        Specification<Notification> mine = (root, query, cb) -> cb.equal(root.get("userId"), userId);
        return notifications.findAll(mine.and(specification(params)), params.pageable(SORTABLE, DEFAULT_SORT));
    }

    /**
     * {@code q} and {@code title[_like|_ne]} on the title, {@code level[_ne]}, and
     * {@code read[_ne]=true|false} ({@code unread} kept for older callers). A value
     * that cannot be a level matches nothing rather than everything.
     */
    static Specification<Notification> specification(ListParams params) {
        List<Specification<Notification>> specs = new ArrayList<>();
        specs.add(BaseEntitySpecifications.notDeleted());
        for (ListParams.Criterion criterion : params.filters()) {
            String value = criterion.value().trim();
            boolean negated = criterion.operator() == ListParams.Operator.NE;
            switch (criterion.field()) {
                case "title" -> specs.add((root, query, cb) -> switch (criterion.operator()) {
                    case LIKE -> cb.like(cb.lower(root.get("title")), ListParams.likePattern(value));
                    case NE -> cb.notEqual(root.get("title"), value);
                    default -> cb.equal(root.get("title"), value);
                });
                case "level" -> {
                    NotificationLevel level;
                    try {
                        level = NotificationLevel.valueOf(value.toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException e) {
                        specs.add((root, query, cb) -> cb.disjunction());
                        continue;
                    }
                    specs.add((root, query, cb) -> negated
                            ? cb.notEqual(root.get("level"), level)
                            : cb.equal(root.get("level"), level));
                }
                case "read" -> {
                    boolean read = "true".equalsIgnoreCase(value);
                    if (!read && !"false".equalsIgnoreCase(value) && !"unread".equalsIgnoreCase(value)) {
                        specs.add((root, query, cb) -> cb.disjunction());
                        continue;
                    }
                    boolean wantRead = read != negated;
                    specs.add((root, query, cb) -> wantRead
                            ? cb.isNotNull(root.get("readAt"))
                            : cb.isNull(root.get("readAt")));
                }
                default -> {
                }
            }
        }
        params.search().ifPresent(term -> specs.add((root, query, cb) ->
                cb.like(cb.lower(root.get("title")), ListParams.likePattern(term))));
        return Specification.allOf(specs);
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
