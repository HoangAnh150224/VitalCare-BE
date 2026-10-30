package com.vn.vitalcare.care.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.care.clinic.service.ClinicService;
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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class SupportChatTest {

    private static final UUID CLINIC = UUID.fromString("5b1f8c2e-6a3d-4f7e-9c41-2d8e0a7b3c19");

    private ConversationRepository conversations;
    private MessageRepository messages;
    private ClinicService clinics;
    private ApplicationEventPublisher events;
    private SupportChat chat;
    private Conversation stored;
    private final List<Message> written = new ArrayList<>();

    @BeforeEach
    void setUp() {
        conversations = mock(ConversationRepository.class);
        messages = mock(MessageRepository.class);
        clinics = mock(ClinicService.class);
        events = mock(ApplicationEventPublisher.class);
        Clinic clinic = new Clinic();
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
        RowLevelSecurity rowLevel = mock(RowLevelSecurity.class);
        when(rowLevel.byId(eq(Conversation.class), any(), any()))
                .thenReturn((root, query, cb) -> cb.conjunction());
        chat = new SupportChat(conversations, messages, clinics, mock(UserService.class), mock(FileStorage.class),
                rowLevel, events, new TickingClock());
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("a guest starts with a name, a phone and a message; only the token's hash is kept")
    void guestStarts() {
        ChatResponses.GuestStarted started = chat.startAsGuest(guestStart("0901 234 567"), "1.1.1.1");

        assertEquals(9L, started.conversation().id());
        assertEquals("+84901234567", stored.getContactPhone());
        assertNotEquals(started.token(), stored.getGuestTokenHash());
        assertEquals(SupportChat.hash(started.token()), stored.getGuestTokenHash());
        assertEquals(ChatSenderKind.GUEST, stored.getLastSenderKind());
        assertEquals("Cho hỏi giờ khám", stored.getLastMessagePreview());
        assertTrue(stored.isGuest());
    }

    @Test
    @DisplayName("the right token opens the conversation; a wrong or missing one is not found")
    void guestToken() {
        String token = chat.startAsGuest(guestStart("0901234567"), "1.1.1.1").token();

        assertEquals(1, chat.guestThread(9L, token).messages().size());
        assertThrows(ResourceNotFoundException.class, () -> chat.guestThread(9L, token + "x"));
        assertThrows(ResourceNotFoundException.class, () -> chat.guestThread(9L, null));
        assertThrows(ResourceNotFoundException.class, () -> chat.sendAsGuest(9L, "", "hi", "1.1.1.1"));
    }

    @Test
    @DisplayName("one address may start five conversations an hour")
    void startLimit() {
        for (int i = 0; i < 5; i++) {
            chat.startAsGuest(guestStart("0901234567"), "2.2.2.2");
        }
        assertThrows(ConflictException.class, () -> chat.startAsGuest(guestStart("0901234567"), "2.2.2.2"));
        chat.startAsGuest(guestStart("0901234567"), "3.3.3.3");
    }

    @Test
    @DisplayName("a bad phone, an unknown clinic, a closed clinic and an empty message are refused")
    void validation() {
        assertThrows(FieldValidationException.class, () -> chat.startAsGuest(guestStart("12"), "1.1.1.1"));
        UUID unknown = UUID.randomUUID();
        when(clinics.get(unknown)).thenThrow(new ResourceNotFoundException("Clinic", unknown));
        assertThrows(FieldValidationException.class, () -> chat.startAsGuest(
                new ChatRequests.GuestStart(unknown, "Lan", "0901234567", "hi"), "1.1.1.1"));
        clinics.get(CLINIC).setOperatingStatus("INACTIVE");
        assertThrows(FieldValidationException.class, () -> chat.startAsGuest(guestStart("0901234567"), "1.1.1.1"));
        clinics.get(CLINIC).setOperatingStatus("OPERATING");

        String token = chat.startAsGuest(guestStart("0901234567"), "1.1.1.1").token();
        assertThrows(FieldValidationException.class, () -> chat.sendAsGuest(9L, token, "   ", "1.1.1.1"));
    }

    @Test
    @DisplayName("only PNG and JPEG images whose bytes match their type are taken")
    void images() {
        String token = chat.startAsGuest(guestStart("0901234567"), "1.1.1.1").token();
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};

        chat.attachAsGuest(9L, token, new MockMultipartFile("file", "a.png", "image/png", png), "1.1.1.1");
        assertEquals(Message.IMAGE, written.getLast().getMessageType());
        assertEquals("[Ảnh]", stored.getLastMessagePreview());

        assertThrows(FieldValidationException.class, () -> chat.attachAsGuest(9L, token,
                new MockMultipartFile("file", "a.pdf", "application/pdf", "%PDF".getBytes()), "1.1.1.1"));
        assertThrows(FieldValidationException.class, () -> chat.attachAsGuest(9L, token,
                new MockMultipartFile("file", "a.png", "image/png", "not a png".getBytes()), "1.1.1.1"));
    }

    @Test
    @DisplayName("reading marks the conversation read only when the other side wrote since, so a nudge does not nudge back")
    void readOnce() {
        String token = chat.startAsGuest(guestStart("0901234567"), "1.1.1.1").token();
        signIn(5L);
        when(conversations.findOne(any(org.springframework.data.jpa.domain.Specification.class))).thenAnswer(call -> Optional.of(stored));
        chat.reply(9L, "Chào anh/chị");
        assertEquals(ChatSenderKind.STAFF, stored.getLastSenderKind());

        clearInvocations(events);
        chat.guestThread(9L, token);
        verify(events).publishEvent(any(ChatPublisher.Moved.class));
        clearInvocations(events);
        chat.guestThread(9L, token);
        verify(events, never()).publishEvent(any());

        // Staff opening it now changes nothing either: staff wrote last.
        chat.staffThread(9L);
        verify(events, never()).publishEvent(any());
    }

    @Test
    @DisplayName("a staff reply is read by the guest once they open it; the guest writing reopens a closed conversation")
    void readAndReopen() {
        String token = chat.startAsGuest(guestStart("0901234567"), "1.1.1.1").token();
        signIn(5L);
        when(conversations.findOne(any(org.springframework.data.jpa.domain.Specification.class))).thenAnswer(call -> Optional.of(stored));
        chat.reply(9L, "Chào anh/chị");
        assertFalse(chat.staffThread(9L).messages().getLast().read());

        chat.guestThread(9L, token);
        signIn(5L);
        assertTrue(chat.staffThread(9L).messages().getLast().read());

        chat.setOpen(9L, false);
        assertEquals(Conversation.CLOSED, stored.getStatus());
        chat.sendAsGuest(9L, token, "Còn một câu nữa", "1.1.1.1");
        assertEquals(Conversation.OPEN, stored.getStatus());
    }

    @Test
    @DisplayName("a customer reaches only their own conversations")
    void ownOnly() {
        signIn(77L);
        when(conversations.findOwn(anyLong(), eq(77L))).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> chat.ownThread(9L));
        assertThrows(ResourceNotFoundException.class, () -> chat.sendAsCustomer(9L, "hi"));
    }

    /** A second later on every reading, so "after" and "before" mean something between two steps. */
    private static final class TickingClock extends Clock {
        private final java.util.concurrent.atomic.AtomicLong seconds = new java.util.concurrent.atomic.AtomicLong();

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.parse("2026-10-30T03:00:00Z").plusSeconds(seconds.incrementAndGet());
        }
    }

    private static ChatRequests.GuestStart guestStart(String phone) {
        return new ChatRequests.GuestStart(CLINIC, "Nguyễn Thị Lan", phone, "Cho hỏi giờ khám");
    }

    private static void signIn(long userId) {
        Jwt token = Jwt.withTokenValue("test").header("alg", "none").subject(String.valueOf(userId))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(token));
    }
}
