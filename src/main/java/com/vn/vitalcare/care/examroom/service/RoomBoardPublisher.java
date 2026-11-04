package com.vn.vitalcare.care.examroom.service;

import java.util.Map;
import java.util.UUID;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Tells open boards of a clinic that it changed.
 *
 * <p>The message says which clinic and nothing else: a subscription is
 * checked against {@code exam_rooms:read} only, not against the clinic, so the
 * board itself — names, queue numbers — is fetched over HTTP, through the
 * clinic scope. ponytail: single-node in-memory broker; fan out over
 * {@code PostgresEventBus}, as {@code AuthorityChangeChannel} does, when the
 * API runs on more than one node.
 */
@Component
public class RoomBoardPublisher {

    /** {@code /topic/exam_rooms/{clinicId}}; the first segment is the permission a subscriber needs. */
    public static final String TOPIC = "/topic/exam_rooms/";

    private final SimpMessagingTemplate messaging;

    public RoomBoardPublisher(SimpMessagingTemplate messaging) {
        this.messaging = messaging;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onBoardChanged(ExamRoomEvents.BoardChanged event) {
        send(event.clinicId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRoomFreed(ExamRoomEvents.RoomFreed event) {
        send(event.clinicId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onQueueJoined(ExamRoomEvents.QueueJoined event) {
        send(event.clinicId());
    }

    private void send(UUID clinicId) {
        if (clinicId != null) {
            messaging.convertAndSend(TOPIC + clinicId, (Object) Map.of("clinicId", clinicId.toString()));
        }
    }
}
