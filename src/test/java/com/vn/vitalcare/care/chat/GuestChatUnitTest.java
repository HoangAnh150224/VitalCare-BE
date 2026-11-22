package com.vn.vitalcare.care.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.care.clinic.service.ClinicService;
import com.vn.vitalcare.care.staff.service.ClinicScope;
import com.vn.vitalcare.entity.ChatSenderKind;
import com.vn.vitalcare.entity.Clinic;
import com.vn.vitalcare.entity.Conversation;
import com.vn.vitalcare.entity.Message;
import com.vn.vitalcare.identity.rowlevel.service.RowLevelSecurity;
import com.vn.vitalcare.identity.user.service.UserService;
import com.vn.vitalcare.share.exception.ConflictException;
import com.vn.vitalcare.share.exception.FieldValidationException;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import com.vn.vitalcare.share.storage.FileStorage;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;

/** UC Chat with Clinic (guest): request validation, SupportChat's guest door and the controller. */
class GuestChatUnitTest {

    private static final UUID CLINIC = UUID.fromString("5b1f8c2e-6a3d-4f7e-9c41-2d8e0a7b3c19");

    private ConversationRepository conversations;
    private MessageRepository messages;
    private ClinicService clinics;
    private FileStorage storage;
    private SupportChat chat;
    private Clinic clinic;
    private Conversation stored;
    private final List<Message> written = new ArrayList<>();

    @BeforeEach
    void setUp() {
        conversations = mock(ConversationRepository.class);
        messages = mock(MessageRepository.class);
        clinics = mock(ClinicService.class);
        storage = mock(FileStorage.class);
        when(storage.store(any())).thenReturn("key-1");
        clinic = new Clinic();
        clinic.setClinicId(CLINIC);
        clinic.setClinicName("VitalCare Clinic");
        clinic.setOperatingStatus("OPERATING");
        when(clinics.get(CLINIC)).thenReturn(clinic);
        when(conversations.persist(any(Conversation.class))).thenAnswer(call -> {
            Conversation conversation = call.getArgument(0);
            if (conversation.getId() == null) {
                conversation.setId(9L);
            }
            stored = conversation;
            return conversation;
        });
        when(conversations.findGuest(9L)).thenAnswer(call -> Optional.ofNullable(stored));
        when(messages.save(any(Message.class))).thenAnswer(call -> {
            Message message = call.getArgument(0);
            message.setId((long) written.size() + 1);
            written.add(message);
            return message;
        });
        when(messages.findLatest(eq(9L), any())).thenAnswer(call -> written.reversed());
        chat = new SupportChat(conversations, messages, clinics, mock(ClinicScope.class), mock(UserService.class),
                storage, mock(RowLevelSecurity.class), mock(ApplicationEventPublisher.class),
                mock(com.vn.vitalcare.care.notification.Notifier.class),
                mock(com.vn.vitalcare.care.sharing.SharedPatients.class), new TickingClock());
    }

    private static ChatRequests.GuestStart start(String phone) {
        return new ChatRequests.GuestStart(CLINIC, "Nguyễn Thị Lan", phone, "Cho hỏi giờ khám");
    }

    @Nested
    @DisplayName("ChatRequests.GuestStart (@Valid in GuestChatController.start)")
    class GuestStartValidation {

        private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

        private Set<String> errors(UUID clinicId, String name, String phone, String text) {
            return validator.validate(new ChatRequests.GuestStart(clinicId, name, phone, text)).stream()
                    .map(v -> v.getPropertyPath() + ": " + v.getMessage()).collect(Collectors.toSet());
        }

        @Test void utc01_valid() {
            assertEquals(Set.of(), errors(CLINIC, "Lan", "0901234567", "Cho hỏi giờ khám"));
        }

        @Test void utc02_noClinic() {
            assertEquals(Set.of("clinicId: Pick a clinic"), errors(null, "Lan", "0901234567", "hi"));
        }

        @Test void utc03_blankNameAndPhone() {
            assertEquals(Set.of("name: Give your name", "phone: Give a phone number the clinic can call back"),
                    errors(CLINIC, " ", "", "hi"));
        }

        @Test void utc04_name100() {
            assertEquals(Set.of(), errors(CLINIC, "a".repeat(100), "0901234567", "hi"));
        }

        @Test void utc05_name101() {
            assertEquals(Set.of("name: The name must be at most 100 characters"),
                    errors(CLINIC, "a".repeat(101), "0901234567", "hi"));
        }

        @Test void utc06_text2001() {
            assertEquals(Set.of("text: A message must be at most 2000 characters"),
                    errors(CLINIC, "Lan", "0901234567", "a".repeat(2001)));
        }

        @Test void utc07_textBlank() {
            assertEquals(Set.of("text: Write a message"), errors(CLINIC, "Lan", "0901234567", "  "));
        }
    }

    @Nested
    @DisplayName("SupportChat.startAsGuest")
    class StartAsGuest {

        @Test void utc08_starts() {
            ChatResponses.GuestStarted started = chat.startAsGuest(start("0901 234 567"), "1.1.1.1");
            assertEquals(9L, started.conversation().id());
            assertEquals("+84901234567", stored.getContactPhone());
            assertEquals("Nguyễn Thị Lan", stored.getContactName());
            assertNotEquals(started.token(), stored.getGuestTokenHash());
            assertEquals(SupportChat.hash(started.token()), stored.getGuestTokenHash());
            assertEquals(1, written.size());
            assertEquals(ChatSenderKind.GUEST, written.getFirst().getSenderKind());
            assertEquals("Cho hỏi giờ khám", written.getFirst().getContent());
        }

        @Test void utc09_badPhone() {
            FieldValidationException e = assertThrows(FieldValidationException.class,
                    () -> chat.startAsGuest(start("12"), "1.1.1.1"));
            assertEquals("phone", e.field());
            assertEquals("Give a valid phone number", e.getMessage());
            verify(conversations, never()).persist(any());
        }

        @Test void utc10_unknownClinic() {
            UUID unknown = UUID.randomUUID();
            when(clinics.get(unknown)).thenThrow(new ResourceNotFoundException("Clinic", unknown));
            FieldValidationException e = assertThrows(FieldValidationException.class, () -> chat.startAsGuest(
                    new ChatRequests.GuestStart(unknown, "Lan", "0901234567", "hi"), "1.1.1.1"));
            assertEquals("clinicId", e.field());
            assertEquals("Pick one of the clinics", e.getMessage());
        }

        @Test void utc11_closedClinic() {
            clinic.setOperatingStatus("INACTIVE");
            FieldValidationException e = assertThrows(FieldValidationException.class,
                    () -> chat.startAsGuest(start("0901234567"), "1.1.1.1"));
            assertEquals("That clinic is not taking messages", e.getMessage());
        }

        @Test void utc12_fivePerHourPerAddress() {
            for (int i = 0; i < 5; i++) {
                chat.startAsGuest(start("0901234567"), "2.2.2.2");
            }
            ConflictException e = assertThrows(ConflictException.class,
                    () -> chat.startAsGuest(start("0901234567"), "2.2.2.2"));
            assertTrue(e.getMessage().startsWith("Too many conversations have been started from this network"));
            chat.startAsGuest(start("0901234567"), "3.3.3.3");
        }

        @Test void utc13_badPhoneDoesNotCount() {
            for (int i = 0; i < 5; i++) {
                assertThrows(FieldValidationException.class, () -> chat.startAsGuest(start("12"), "4.4.4.4"));
            }
            chat.startAsGuest(start("0901234567"), "4.4.4.4");
        }
    }

    @Nested
    @DisplayName("SupportChat.guestThread / sendAsGuest / attachAsGuest")
    class GuestConversation {

        private String token;

        @BeforeEach
        void started() {
            token = chat.startAsGuest(start("0901234567"), "1.1.1.1").token();
        }

        @Test void utc14_rightToken() {
            ChatResponses.Thread thread = chat.guestThread(9L, token);
            assertEquals(1, thread.messages().size());
            assertEquals("Cho hỏi giờ khám", thread.messages().getFirst().text());
        }

        @Test void utc15_wrongOrMissingToken() {
            assertThrows(ResourceNotFoundException.class, () -> chat.guestThread(9L, token + "x"));
            assertThrows(ResourceNotFoundException.class, () -> chat.guestThread(9L, null));
            ResourceNotFoundException e = assertThrows(ResourceNotFoundException.class,
                    () -> chat.guestThread(9L, " "));
            assertEquals("Conversation with id 9 was not found", e.getMessage());
        }

        @Test void utc16_send() {
            chat.sendAsGuest(9L, token, "  Còn một câu nữa  ", "1.1.1.1");
            assertEquals("Còn một câu nữa", written.getLast().getContent());
            assertEquals("Còn một câu nữa", stored.getLastMessagePreview());
            assertEquals(Conversation.OPEN, stored.getStatus());
        }

        @Test void utc17_sendBlank() {
            FieldValidationException e = assertThrows(FieldValidationException.class,
                    () -> chat.sendAsGuest(9L, token, "   ", "1.1.1.1"));
            assertEquals("Write a message", e.getMessage());
        }

        @Test void utc18_thirtyMessagesPerTenMinutes() {
            for (int i = 0; i < 30; i++) {
                chat.sendAsGuest(9L, token, "tin " + i, "5.5.5.5");
            }
            ConflictException e = assertThrows(ConflictException.class,
                    () -> chat.sendAsGuest(9L, token, "tin 31", "5.5.5.5"));
            assertTrue(e.getMessage().startsWith("Too many messages from this network"));
        }

        @Test void utc19_png() {
            byte[] png = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
            chat.attachAsGuest(9L, token, new MockMultipartFile("file", "a.png", "image/png", png), "1.1.1.1");
            assertEquals(Message.IMAGE, written.getLast().getMessageType());
            assertEquals("[Ảnh]", stored.getLastMessagePreview());
        }

        @Test void utc20_pdf() {
            FieldValidationException e = assertThrows(FieldValidationException.class, () -> chat.attachAsGuest(9L,
                    token, new MockMultipartFile("file", "a.pdf", "application/pdf", "%PDF".getBytes()), "1.1.1.1"));
            assertEquals("Only PNG and JPEG images are accepted", e.getMessage());
        }

        @Test void utc21_fakePng() {
            FieldValidationException e = assertThrows(FieldValidationException.class, () -> chat.attachAsGuest(9L,
                    token, new MockMultipartFile("file", "a.png", "image/png", "not a png".getBytes()), "1.1.1.1"));
            assertEquals("The file's content does not match its type", e.getMessage());
        }

        @Test void utc22_tooLarge() {
            byte[] big = new byte[(int) SupportChat.MAX_IMAGE_BYTES + 1];
            big[0] = (byte) 0x89;
            FieldValidationException e = assertThrows(FieldValidationException.class, () -> chat.attachAsGuest(9L,
                    token, new MockMultipartFile("file", "a.png", "image/png", big), "1.1.1.1"));
            assertEquals("An image can be at most 5 MB", e.getMessage());
        }

        @Test void utc23_hash() {
            String h = SupportChat.hash("abc");
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", h);
            assertEquals(h, SupportChat.hash("abc"));
        }
    }

    @Nested
    @DisplayName("GuestChatController")
    class Controller {

        @Test void utc24_startAnswers201() {
            GuestChatController controller = new GuestChatController(chat);
            MockHttpServletRequest http = new MockHttpServletRequest();
            http.setRemoteAddr("6.6.6.6");
            ResponseEntity<ChatResponses.GuestStarted> response = controller.start(start("0901234567"), http);
            assertEquals(HttpStatus.CREATED, response.getStatusCode());
            assertEquals(9L, response.getBody().conversation().id());
            assertEquals("X-Chat-Token", GuestChatController.TOKEN);
        }
    }

    /** A second later on every reading. */
    private static final class TickingClock extends Clock {
        private final AtomicLong seconds = new AtomicLong();

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.parse("2026-10-30T03:00:00Z").plusSeconds(seconds.incrementAndGet());
        }
    }
}
