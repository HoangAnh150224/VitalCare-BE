package com.vn.vitalcare.care.notification;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Pushes a notification to its account once the row is committed; one rolled back is never pushed. */
@Component
public class NotificationPush {

    private final SimpMessagingTemplate messaging;

    public NotificationPush(SimpMessagingTemplate messaging) {
        this.messaging = messaging;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void push(Notifier.Raised raised) {
        messaging.convertAndSendToUser(String.valueOf(raised.userId()), Notifier.DESTINATION, raised.notification());
    }
}
