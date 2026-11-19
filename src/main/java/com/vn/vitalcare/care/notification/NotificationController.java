package com.vn.vitalcare.care.notification;

import com.vn.vitalcare.entity.Notification;
import com.vn.vitalcare.share.security.CurrentUser;
import com.vn.vitalcare.share.security.Permissions;
import com.vn.vitalcare.share.web.ListParams;
import com.vn.vitalcare.share.web.ListResponse;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/notifications} — the caller's own notifications (FE-13): the
 * Notification Center's list, the bell's recent ones and unread count, and
 * marking them read. Every query is pinned to the caller's account.
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final Notifier notifier;

    public NotificationController(Notifier notifier) {
        this.notifier = notifier;
    }

    /** A page of them, newest first unless sorted; simple-rest paging, {@code q} and field filters (see {@link Notifier#own}). */
    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.NOTIFICATIONS_READ + "')")
    public ResponseEntity<List<NotificationResponse>> list(@RequestParam MultiValueMap<String, String> query) {
        Page<Notification> page = notifier.own(me(), new ListParams(query));
        return ListResponse.of(page.getContent().stream().map(NotificationResponse::from).toList(),
                page.getTotalElements());
    }

    @GetMapping("/recent")
    @PreAuthorize("hasAuthority('" + Permissions.NOTIFICATIONS_READ + "')")
    public List<NotificationResponse> recent() {
        return notifier.recent(me()).stream().map(NotificationResponse::from).toList();
    }

    @GetMapping("/unread-count")
    @PreAuthorize("hasAuthority('" + Permissions.NOTIFICATIONS_READ + "')")
    public Map<String, Long> unreadCount() {
        return Map.of("unread", notifier.unread(me()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.NOTIFICATIONS_READ + "')")
    public NotificationResponse get(@PathVariable Long id) {
        return NotificationResponse.from(notifier.get(me(), id));
    }

    @PostMapping("/{id}/read")
    @PreAuthorize("hasAuthority('" + Permissions.NOTIFICATIONS_WRITE + "')")
    public NotificationResponse markRead(@PathVariable Long id) {
        return NotificationResponse.from(notifier.markRead(me(), id));
    }

    @PostMapping("/read-all")
    @PreAuthorize("hasAuthority('" + Permissions.NOTIFICATIONS_WRITE + "')")
    public Map<String, Integer> markAllRead() {
        return Map.of("updated", notifier.markAllRead(me()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.NOTIFICATIONS_WRITE + "')")
    public ResponseEntity<Void> remove(@PathVariable Long id) {
        notifier.remove(me(), id);
        return ResponseEntity.noContent().build();
    }

    private static Long me() {
        return CurrentUser.id().orElseThrow(() -> new AuthorizationDeniedException("Not signed in"));
    }
}
