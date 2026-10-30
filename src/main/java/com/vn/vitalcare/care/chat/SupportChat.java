package com.vn.vitalcare.care.chat;

import com.vn.vitalcare.care.clinic.service.ClinicService;
import com.vn.vitalcare.entity.ChatSenderKind;
import com.vn.vitalcare.entity.Clinic;
import com.vn.vitalcare.entity.Conversation;
import com.vn.vitalcare.entity.Message;
import com.vn.vitalcare.identity.rowlevel.service.RowLevelSecurity;
import com.vn.vitalcare.identity.user.entity.User;
import com.vn.vitalcare.identity.user.service.UserService;
import com.vn.vitalcare.share.data.BaseEntitySpecifications;
import com.vn.vitalcare.share.exception.FieldValidationException;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import com.vn.vitalcare.share.phone.PhoneNumbers;
import com.vn.vitalcare.share.security.CurrentUser;
import com.vn.vitalcare.share.security.rowlevel.Action;
import com.vn.vitalcare.share.storage.FileStorage;
import com.vn.vitalcare.share.storage.UploadedFiles;
import com.vn.vitalcare.share.web.IpWindowLimiter;
import com.vn.vitalcare.share.web.ListParams;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Support chat (SRS 3.16, BR-30) between a clinic's staff and the people
 * writing to it: customers signed in, and guests who are not.
 *
 * <p>Three doors onto the same conversations, each checked its own way:
 * staff through their row-level scope (their clinic's conversations), a
 * customer through their own account, a guest through the token handed back
 * when they started — only its hash is stored, and a wrong one answers "not
 * found" so it says nothing about which conversations exist. The two doors
 * open to anybody are rate limited per address.
 *
 * <p>Chat is not an emergency service; the screens say so and this class does
 * not route, escalate or time anything.
 */
@Service
@Transactional(readOnly = true)
public class SupportChat {

    /** How many messages a conversation shows; older ones stay in the database. */
    static final int THREAD_SIZE = 200;
    static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;
    private static final String OPERATING = "OPERATING";

    private static final Set<String> SORTABLE = Set.of("id", "lastMessageAt");
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Order.desc("lastMessageAt"), Sort.Order.desc("id"));

    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final ClinicService clinics;
    private final UserService users;
    private final FileStorage storage;
    private final RowLevelSecurity rowLevel;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final IpWindowLimiter guestStarts;
    private final IpWindowLimiter guestMessages;

    public SupportChat(ConversationRepository conversations, MessageRepository messages, ClinicService clinics,
                       UserService users, FileStorage storage, RowLevelSecurity rowLevel,
                       ApplicationEventPublisher events, Clock clock) {
        this.conversations = conversations;
        this.messages = messages;
        this.clinics = clinics;
        this.users = users;
        this.storage = storage;
        this.rowLevel = rowLevel;
        this.events = events;
        this.clock = clock;
        this.guestStarts = new IpWindowLimiter(Duration.ofHours(1), 5,
                "Too many conversations have been started from this network; try again later", clock);
        this.guestMessages = new IpWindowLimiter(Duration.ofMinutes(10), 30,
                "Too many messages from this network; wait a few minutes", clock);
    }

    /** A stored image, for the attachment endpoints. */
    public record StoredImage(String contentType, long sizeBytes, Resource content) {
    }

    // ---- guests ----------------------------------------------------------------------

    @Transactional
    public ChatResponses.GuestStarted startAsGuest(ChatRequests.GuestStart request, String ip) {
        guestStarts.check(ip);
        Clinic clinic = operatingClinic(request.clinicId());
        String phone = PhoneNumbers.normalize(request.phone())
                .orElseThrow(() -> new FieldValidationException("phone", "Give a valid phone number"));
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);

        Conversation conversation = new Conversation();
        conversation.setClinic(clinic);
        conversation.setContactName(request.name().trim());
        conversation.setContactPhone(phone);
        conversation.setGuestTokenHash(hash(token));
        conversation = conversations.persist(conversation);
        post(conversation, ChatSenderKind.GUEST, null, request.text(), null);
        return new ChatResponses.GuestStarted(ChatResponses.Own.from(conversation, 0), token);
    }

    @Transactional
    public ChatResponses.Thread guestThread(Long id, String token) {
        Conversation conversation = guest(id, token);
        markRead(conversation, ChatSenderKind.GUEST);
        return new ChatResponses.Thread(ChatResponses.Own.from(conversation, 0), lines(conversation, false));
    }

    @Transactional
    public ChatResponses.Line sendAsGuest(Long id, String token, String text, String ip) {
        Conversation conversation = guest(id, token);
        guestMessages.check(ip);
        return line(post(conversation, ChatSenderKind.GUEST, null, text, null), false, Map.of());
    }

    @Transactional
    public ChatResponses.Line attachAsGuest(Long id, String token, MultipartFile file, String ip) {
        Conversation conversation = guest(id, token);
        guestMessages.check(ip);
        return line(post(conversation, ChatSenderKind.GUEST, null, null, file), false, Map.of());
    }

    public StoredImage guestImage(Long id, String token, Long messageId) {
        return image(guest(id, token), messageId);
    }

    // ---- customers -------------------------------------------------------------------

    public List<ChatResponses.Own> ownList() {
        List<Conversation> list = conversations.findOwn(me());
        Map<Long, Long> unread = list.isEmpty() ? Map.of()
                : counts(messages.countUnreadByCustomer(ids(list), ChatSenderKind.STAFF));
        return list.stream().map(c -> ChatResponses.Own.from(c, unread.getOrDefault(c.getId(), 0L))).toList();
    }

    /** Writing to a clinic continues the customer's conversation with it, or starts one. */
    @Transactional
    public ChatResponses.Thread startAsCustomer(ChatRequests.OwnStart request) {
        Long userId = me();
        Clinic clinic = operatingClinic(request.clinicId());
        Conversation conversation = conversations.findOwnAt(userId, clinic.getClinicId()).orElseGet(() -> {
            User user = users.getMany(List.of(userId)).stream().findFirst()
                    .orElseThrow(() -> new ResourceNotFoundException("User", userId));
            Conversation created = new Conversation();
            created.setClinic(clinic);
            created.setCustomerUserId(userId);
            created.setContactName(user.getFullName());
            created.setContactPhone(user.getPhone());
            return conversations.persist(created);
        });
        post(conversation, ChatSenderKind.CUSTOMER, userId, request.text(), null);
        return ownThread(conversation.getId());
    }

    @Transactional
    public ChatResponses.Thread ownThread(Long id) {
        Conversation conversation = own(id);
        markRead(conversation, ChatSenderKind.CUSTOMER);
        return new ChatResponses.Thread(ChatResponses.Own.from(conversation, 0), lines(conversation, false));
    }

    @Transactional
    public ChatResponses.Line sendAsCustomer(Long id, String text) {
        return line(post(own(id), ChatSenderKind.CUSTOMER, me(), text, null), false, Map.of());
    }

    @Transactional
    public ChatResponses.Line attachAsCustomer(Long id, MultipartFile file) {
        return line(post(own(id), ChatSenderKind.CUSTOMER, me(), null, file), false, Map.of());
    }

    public StoredImage ownImage(Long id, Long messageId) {
        return image(own(id), messageId);
    }

    // ---- staff -----------------------------------------------------------------------

    public Page<Conversation> inbox(ListParams params) {
        return conversations.findAll(rowLevel.scope(Conversation.class, Action.READ)
                        .and(ConversationSpecifications.from(params)),
                params.pageable(SORTABLE, DEFAULT_SORT));
    }

    public Map<Long, Long> unreadByStaff(Collection<Conversation> list) {
        if (list.isEmpty()) {
            return Map.of();
        }
        return counts(messages.countUnreadByStaff(ids(list), ChatSenderKind.STAFF));
    }

    /** How many of the caller's conversations wait on the clinic: the sidebar's badge. */
    public long waitingCount() {
        return conversations.count(rowLevel.scope(Conversation.class, Action.READ)
                .and(BaseEntitySpecifications.notDeleted())
                .and(ConversationSpecifications.waitingOnStaff()));
    }

    public Map<String, Boolean> capabilities(Conversation conversation) {
        return rowLevel.capabilities(conversation);
    }

    @Transactional
    public ChatResponses.Thread staffThread(Long id) {
        Conversation conversation = staff(id, Action.READ);
        markRead(conversation, ChatSenderKind.STAFF);
        return new ChatResponses.Thread(ChatResponses.Inbox.from(conversation, 0, capabilities(conversation)),
                lines(conversation, true));
    }

    @Transactional
    public ChatResponses.Line reply(Long id, String text) {
        Long userId = me();
        Message message = post(staff(id, Action.WRITE), ChatSenderKind.STAFF, userId, text, null);
        return line(message, true, names(List.of(message)));
    }

    @Transactional
    public ChatResponses.Line attachAsStaff(Long id, MultipartFile file) {
        Long userId = me();
        Message message = post(staff(id, Action.WRITE), ChatSenderKind.STAFF, userId, null, file);
        return line(message, true, names(List.of(message)));
    }

    public StoredImage staffImage(Long id, Long messageId) {
        return image(staff(id, Action.READ), messageId);
    }

    /** Done with, for now; the patient writing again reopens it. */
    @Transactional
    public Conversation setOpen(Long id, boolean open) {
        Conversation conversation = staff(id, Action.WRITE);
        conversation.setStatus(open ? Conversation.OPEN : Conversation.CLOSED);
        Conversation saved = conversations.persist(conversation);
        publish(saved, ChatSenderKind.STAFF);
        return saved;
    }

    // ---- internals ----------------------------------------------------------------------

    /**
     * Adds a message, text or image, and brings the conversation up to date:
     * its last message, the writer's side as having read it, and — when the
     * patient or guest writes — open again if it had been closed.
     */
    private Message post(Conversation conversation, ChatSenderKind kind, Long senderId, String text,
                         MultipartFile image) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        Message message = new Message();
        message.setSenderKind(kind);
        message.setSenderId(senderId);
        message.setSentAt(now);
        if (image != null) {
            byte[] bytes = UploadedFiles.readChecked(image, UploadedFiles.PNG_JPEG, MAX_IMAGE_BYTES,
                    "An image can be at most 5 MB", "Only PNG and JPEG images are accepted");
            message.setMessageType(Message.IMAGE);
            message.setAttachmentKey(storage.store(new ByteArrayInputStream(bytes)));
            message.setAttachmentType(image.getContentType());
            message.setAttachmentSize((long) bytes.length);
        } else {
            String trimmed = text == null ? "" : text.strip();
            if (trimmed.isEmpty()) {
                throw new FieldValidationException("text", "Write a message");
            }
            message.setMessageType(Message.TEXT);
            message.setContent(trimmed);
        }
        message.setConversation(conversation);
        Message saved = messages.save(message);

        conversation.setLastMessageAt(now);
        conversation.setLastMessagePreview(ChatResponses.preview(saved));
        conversation.setLastSenderKind(kind);
        if (kind == ChatSenderKind.STAFF) {
            conversation.setStaffReadAt(now);
        } else {
            conversation.setCustomerReadAt(now);
            conversation.setStatus(Conversation.OPEN);
        }
        conversations.persist(conversation);
        publish(conversation, kind);
        return saved;
    }

    /**
     * Marks the conversation read by the viewer's side — only when the other
     * side wrote since, so that reading after a nudge does not nudge back.
     */
    private void markRead(Conversation conversation, ChatSenderKind viewer) {
        boolean staff = viewer == ChatSenderKind.STAFF;
        ChatSenderKind last = conversation.getLastSenderKind();
        if (last == null || (last == ChatSenderKind.STAFF) == staff) {
            return;
        }
        OffsetDateTime readAt = staff ? conversation.getStaffReadAt() : conversation.getCustomerReadAt();
        if (readAt != null && !conversation.getLastMessageAt().isAfter(readAt)) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (staff) {
            conversation.setStaffReadAt(now);
        } else {
            conversation.setCustomerReadAt(now);
        }
        conversations.persist(conversation);
        publish(conversation, viewer);
    }

    private void publish(Conversation conversation, ChatSenderKind by) {
        events.publishEvent(new ChatPublisher.Moved(conversation.getId(), conversation.getClinicId(),
                conversation.getCustomerUserId(), by));
    }

    private List<ChatResponses.Line> lines(Conversation conversation, boolean staffViewing) {
        List<Message> latest = new ArrayList<>(messages.findLatest(conversation.getId(), PageRequest.of(0, THREAD_SIZE)));
        Collections.reverse(latest);
        Map<Long, String> names = names(latest);
        return latest.stream().map(message -> line(message, staffViewing, names, conversation)).toList();
    }

    private ChatResponses.Line line(Message message, boolean staffViewing, Map<Long, String> names) {
        return line(message, staffViewing, names, message.getConversation());
    }

    private ChatResponses.Line line(Message message, boolean staffViewing, Map<Long, String> names,
                                    Conversation conversation) {
        boolean byStaff = message.getSenderKind() == ChatSenderKind.STAFF;
        boolean mine = byStaff == staffViewing;
        OffsetDateTime otherRead = byStaff ? conversation.getCustomerReadAt() : conversation.getStaffReadAt();
        boolean read = otherRead != null && !message.getSentAt().isAfter(otherRead);
        String sender = byStaff
                ? Objects.requireNonNullElse(names.get(message.getSenderId()), "Phòng khám")
                : conversation.getContactName();
        return new ChatResponses.Line(message.getId(), message.getSenderKind(), sender, mine,
                message.getContent(), Message.IMAGE.equals(message.getMessageType()), message.getSentAt(), read);
    }

    private Map<Long, String> names(List<Message> list) {
        List<Long> ids = list.stream().map(Message::getSenderId).filter(Objects::nonNull).distinct().toList();
        Map<Long, String> names = new HashMap<>();
        if (!ids.isEmpty()) {
            users.getMany(ids).forEach(user -> names.put(user.getId(), user.getFullName()));
        }
        return names;
    }

    private StoredImage image(Conversation conversation, Long messageId) {
        Message message = messages.findIn(conversation.getId(), messageId)
                .filter(found -> found.getAttachmentKey() != null)
                .orElseThrow(() -> new ResourceNotFoundException("Image", messageId));
        return new StoredImage(message.getAttachmentType(), message.getAttachmentSize(),
                storage.load(message.getAttachmentKey()));
    }

    private Conversation guest(Long id, String token) {
        if (token == null || token.isBlank()) {
            throw new ResourceNotFoundException("Conversation", id);
        }
        byte[] given = hash(token).getBytes(StandardCharsets.US_ASCII);
        return conversations.findGuest(id)
                .filter(found -> MessageDigest.isEqual(found.getGuestTokenHash().getBytes(StandardCharsets.US_ASCII), given))
                .orElseThrow(() -> new ResourceNotFoundException("Conversation", id));
    }

    private Conversation own(Long id) {
        return conversations.findOwn(id, me()).orElseThrow(() -> new ResourceNotFoundException("Conversation", id));
    }

    private Conversation staff(Long id, Action action) {
        return conversations.findOne(Specification.allOf(
                        rowLevel.byId(Conversation.class, action, id), BaseEntitySpecifications.notDeleted()))
                .orElseThrow(() -> new ResourceNotFoundException("Conversation", id));
    }

    private Clinic operatingClinic(UUID clinicId) {
        Clinic clinic;
        try {
            clinic = clinics.get(clinicId);
        } catch (ResourceNotFoundException e) {
            throw new FieldValidationException("clinicId", "Pick one of the clinics");
        }
        if (!OPERATING.equals(clinic.getOperatingStatus())) {
            throw new FieldValidationException("clinicId", "That clinic is not taking messages");
        }
        return clinic;
    }

    private static Long me() {
        return CurrentUser.id().orElseThrow(() -> new AuthorizationDeniedException("Not signed in"));
    }

    private static List<Long> ids(Collection<Conversation> list) {
        return list.stream().map(Conversation::getId).toList();
    }

    private static Map<Long, Long> counts(List<Object[]> rows) {
        Map<Long, Long> counts = new HashMap<>();
        rows.forEach(row -> counts.put((Long) row[0], (Long) row[1]));
        return counts;
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

}
