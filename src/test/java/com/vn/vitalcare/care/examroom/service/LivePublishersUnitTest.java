package com.vn.vitalcare.care.examroom.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.care.appointment.repository.AppointmentRepository;
import com.vn.vitalcare.care.clinic.service.ClinicService;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/** Report-5.1 BE_Infra: RoomBoardPublisher and MyQueuePublisher, the live nudges of FE-10 Exam Room Board / Live Queue. */
class LivePublishersUnitTest {

    private static final UUID CLINIC = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 22);

    private final SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
    private final AppointmentRepository appointments = mock(AppointmentRepository.class);
    private final ClinicService clinics = mock(ClinicService.class);
    private final RoomBoardPublisher board = new RoomBoardPublisher(messaging);
    private final MyQueuePublisher queue = new MyQueuePublisher(messaging, appointments, clinics);

    @Test
    @DisplayName("Each board event tells /topic/exam_rooms/{clinic} which clinic changed, and nothing else")
    void lp01_boardTopicPerClinic() {
        board.onBoardChanged(new ExamRoomEvents.BoardChanged(CLINIC));
        board.onRoomFreed(new ExamRoomEvents.RoomFreed(CLINIC, 3L));
        board.onQueueJoined(new ExamRoomEvents.QueueJoined(CLINIC, 9L));
        verify(messaging, org.mockito.Mockito.times(3))
                .convertAndSend("/topic/exam_rooms/" + CLINIC, (Object) Map.of("clinicId", CLINIC.toString()));
    }

    @Test
    @DisplayName("Boundary: an event with no clinic sends nothing, on the board or the patients' queue")
    void lp02_noClinicNoMessage() {
        board.onBoardChanged(new ExamRoomEvents.BoardChanged(null));
        queue.onBoardChanged(new ExamRoomEvents.BoardChanged(null));
        verifyNoInteractions(messaging, appointments);
    }

    @Test
    @DisplayName("The queue moving nudges every account with a visit in today's queue on its own /queue/my_appointments")
    void lp03_queueNudgesEachAccount() {
        when(clinics.today()).thenReturn(TODAY);
        when(appointments.findQueueUserIds(CLINIC, TODAY)).thenReturn(List.of(11L, 12L));
        queue.onQueueJoined(new ExamRoomEvents.QueueJoined(CLINIC, 9L));
        Map<String, String> nudge = Map.of("clinicId", CLINIC.toString());
        verify(messaging).convertAndSendToUser("11", MyQueuePublisher.DESTINATION, nudge);
        verify(messaging).convertAndSendToUser("12", MyQueuePublisher.DESTINATION, nudge);
    }

    @Test
    @DisplayName("Nobody waiting today: no message is sent")
    void lp04_emptyQueueSendsNothing() {
        when(clinics.today()).thenReturn(TODAY);
        when(appointments.findQueueUserIds(CLINIC, TODAY)).thenReturn(List.of());
        queue.onRoomFreed(new ExamRoomEvents.RoomFreed(CLINIC, 3L));
        queue.onBoardChanged(new ExamRoomEvents.BoardChanged(CLINIC));
        verify(messaging, never()).convertAndSendToUser(anyString(), anyString(), any());
    }
}
