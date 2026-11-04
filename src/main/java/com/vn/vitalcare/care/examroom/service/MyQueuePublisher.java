package com.vn.vitalcare.care.examroom.service;

import com.vn.vitalcare.care.appointment.repository.AppointmentRepository;
import com.vn.vitalcare.care.clinic.service.ClinicService;
import java.util.Map;
import java.util.UUID;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Tells the patients waiting at a clinic that its queue moved, so the portal
 * can show "two people before you" and "your turn, room 3" without a reload.
 *
 * <p>Sent to each account on its own {@code /user/queue/my_appointments}
 * (subscribing needs {@code my_appointments:read}), and only a nudge: the
 * client re-reads its own visits over HTTP, so nothing about anybody else
 * travels on the socket. The same three events the room board listens to.
 * ponytail: single-node in-memory broker, as {@link RoomBoardPublisher}; fan
 * out over {@code PostgresEventBus} when the API runs on more than one node.
 */
@Component
public class MyQueuePublisher {

    /** Minus the {@code /user} prefix Spring adds per session. */
    public static final String DESTINATION = "/queue/my_appointments";

    private final SimpMessagingTemplate messaging;
    private final AppointmentRepository appointments;
    private final ClinicService clinics;

    public MyQueuePublisher(SimpMessagingTemplate messaging, AppointmentRepository appointments,
                            ClinicService clinics) {
        this.messaging = messaging;
        this.appointments = appointments;
        this.clinics = clinics;
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
        if (clinicId == null) {
            return;
        }
        Map<String, String> nudge = Map.of("clinicId", clinicId.toString());
        // The broker drops a message for an account with no session here, so no need to check who is online.
        appointments.findQueueUserIds(clinicId, clinics.today())
                .forEach(userId -> messaging.convertAndSendToUser(String.valueOf(userId), DESTINATION, nudge));
    }
}
