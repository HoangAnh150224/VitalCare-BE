package com.vn.vitalcare.care.chat;

import com.vn.vitalcare.entity.ChatSenderKind;
import java.util.Map;
import java.util.UUID;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Tells whoever is watching that a conversation moved, once the message is
 * committed: the clinic's staff on {@code /topic/conversations/{clinicId}}
 * (subscribing needs {@code conversations:read}), and the customer on their
 * own {@code /user/queue/my_conversations}. Only a nudge with the
 * conversation's id; the screens read the messages over HTTP, through their
 * own checks. A guest has no socket and polls.
 * ponytail: single-node in-memory broker, as {@code RoomBoardPublisher}.
 */
@Component
public class ChatPublisher {

    public static final String STAFF_TOPIC = "/topic/conversations/";
    /** Minus the {@code /user} prefix Spring adds per session. */
    public static final String CUSTOMER_QUEUE = "/queue/my_conversations";

    /** A message was written, or a conversation read or closed. */
    public record Moved(Long conversationId, UUID clinicId, Long customerUserId, ChatSenderKind by) {
    }

    private final SimpMessagingTemplate messaging;

    public ChatPublisher(SimpMessagingTemplate messaging) {
        this.messaging = messaging;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onMoved(Moved event) {
        Object nudge = Map.of("conversationId", event.conversationId());
        if (event.clinicId() != null) {
            messaging.convertAndSend(STAFF_TOPIC + event.clinicId(), nudge);
        }
        if (event.customerUserId() != null) {
            messaging.convertAndSendToUser(String.valueOf(event.customerUserId()), CUSTOMER_QUEUE, nudge);
        }
    }
}
