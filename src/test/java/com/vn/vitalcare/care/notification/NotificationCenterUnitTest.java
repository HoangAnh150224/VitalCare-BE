package com.vn.vitalcare.care.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.entity.Notification;
import com.vn.vitalcare.entity.NotificationLevel;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/** FE-13 Notification Center / Notification Details: Notifier. Report-5.1, sheet BE_Sharing. */
class NotificationCenterUnitTest {

    private static final Instant NOW = Instant.parse("2026-11-22T03:00:00Z");
    private NotificationRepository repository;
    private ApplicationEventPublisher events;
    private Notifier notifier;

    @BeforeEach
    void setUp() {
        repository = mock(NotificationRepository.class);
        events = mock(ApplicationEventPublisher.class);
        notifier = new Notifier(repository, events, Clock.fixed(NOW, ZoneOffset.UTC));
        when(repository.save(any(Notification.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test void nt01_savedAndPushedAfterCommit() {
        Notification n = notifier.notify(42L, NotificationLevel.INFO, "appointment", "Đặt lịch thành công", "Số 5", "/appointments/1");
        assertEquals(42L, n.getUserId());
        assertEquals("Đặt lịch thành công", n.getTitle());
        assertEquals(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC), n.getSentAt());
        ArgumentCaptor<Object> raised = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(raised.capture());
        assertTrue(raised.getValue() instanceof Notifier.Raised);
    }

    @Test void nt02_noAccountNoNotification() {
        assertNull(notifier.notify(null, NotificationLevel.WARNING, "device", "x", "y", null));
        verifyNoInteractions(repository, events);
    }

    @Test void nt03_longTitleBodyLinkAreClipped() {
        Notification n = notifier.notify(42L, NotificationLevel.INFO, "result", "T".repeat(300), "B".repeat(2500), "/" + "l".repeat(600));
        assertEquals(255, n.getTitle().length());
        assertTrue(n.getTitle().endsWith("…"));
        assertEquals(2000, n.getContent().length());
        assertEquals(500, n.getLink().length());
        Notification exact = notifier.notify(42L, NotificationLevel.INFO, "result", "T".repeat(255), "b", null);
        assertEquals("T".repeat(255), exact.getTitle());
    }

    @Test void nt04_markReadStampsTheTime() {
        Notification n = new Notification(42L, NotificationLevel.INFO, "x", "t", "b", null, OffsetDateTime.now());
        when(repository.findOwn(5L, 42L)).thenReturn(Optional.of(n));
        notifier.markRead(42L, 5L);
        assertNotNull(n.getReadAt());
    }

    @Test void nt05_anotherAccountsNotificationIsNotFound() {
        when(repository.findOwn(5L, 43L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> notifier.get(43L, 5L));
        assertThrows(ResourceNotFoundException.class, () -> notifier.markRead(43L, 5L));
        assertThrows(ResourceNotFoundException.class, () -> notifier.remove(43L, 5L));
        verify(repository, never()).save(any());
    }

    @Test void nt06_removeIsSoft() {
        Notification n = new Notification(42L, NotificationLevel.INFO, "x", "t", "b", null, OffsetDateTime.now());
        when(repository.findOwn(5L, 42L)).thenReturn(Optional.of(n));
        notifier.remove(42L, 5L);
        assertNotNull(n.getDeletedAt());
        verify(repository).save(n);
    }

    @Test void nt07_markAllReadAndUnreadCount() {
        when(repository.markAllRead(42L, OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC))).thenReturn(3);
        when(repository.countUnread(42L)).thenReturn(0L);
        assertEquals(3, notifier.markAllRead(42L));
        assertEquals(0L, notifier.unread(42L));
    }
}
