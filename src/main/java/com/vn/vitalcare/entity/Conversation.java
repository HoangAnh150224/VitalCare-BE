package com.vn.vitalcare.entity;

import com.vn.vitalcare.share.data.BaseEntity;
import com.vn.vitalcare.share.security.rowlevel.RowLevelResource;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A support conversation (SRS 3.16) between one clinic's staff and either a
 * customer's account or a guest who has not signed in.
 *
 * <p>A guest proves it is them with a token only its browser holds; only the
 * token's SHA-256 is kept here. {@code contactName}/{@code contactPhone} are
 * what staff see of the other side, as given when the conversation started.
 *
 * <p>The last message's time, a preview of it and who wrote it are kept on
 * the row so the inbox and the "unread" filter need no join into the messages.
 */
@Entity
@RowLevelResource("conversations")
@Table(name = "conversation")
public class Conversation extends BaseEntity {

    public static final String SUPPORT = "SUPPORT";
    public static final String OPEN = "OPEN";
    public static final String CLOSED = "CLOSED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "type")
    private String type = SUPPORT;

    @Column(name = "title")
    private String title;

    /** {@link #OPEN} or {@link #CLOSED}; anything the patient writes reopens it. */
    @Column(name = "status")
    private String status = OPEN;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "clinic_id")
    private Clinic clinic;

    /** The same column as {@link #clinic}, for row-level policies; see {@code Appointment.clinicId}. */
    @Column(name = "clinic_id", insertable = false, updatable = false)
    private UUID clinicId;

    /** The customer's account; null for a guest. */
    @Column(name = "customer_user_id")
    private Long customerUserId;

    @Column(name = "contact_name", length = 100)
    private String contactName;

    @Column(name = "contact_phone", length = 20)
    private String contactPhone;

    @Column(name = "guest_token_hash", length = 64)
    private String guestTokenHash;

    @Column(name = "last_message_at")
    private OffsetDateTime lastMessageAt;

    @Column(name = "last_message_preview", length = 200)
    private String lastMessagePreview;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_sender_kind", length = 16)
    private ChatSenderKind lastSenderKind;

    /** When the patient or guest last read the conversation. */
    @Column(name = "customer_read_at")
    private OffsetDateTime customerReadAt;

    /** When anybody on the clinic's side last read it. */
    @Column(name = "staff_read_at")
    private OffsetDateTime staffReadAt;

    // No orphanRemoval: a message leaves the conversation by getting a
    // deleted_at, not by being taken out of this list.
    @OneToMany(mappedBy = "conversation", cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    private List<Message> messages = new ArrayList<>();

    public Conversation() {
    }

    public void addMessage(Message message) {
        messages.add(message);
        message.setConversation(this);
    }

    public boolean isGuest() {
        return customerUserId == null;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public List<Message> getMessages() {
        return messages;
    }

    public Clinic getClinic() {
        return clinic;
    }

    public void setClinic(Clinic clinic) {
        this.clinic = clinic;
        this.clinicId = clinic == null ? null : clinic.getClinicId();
    }

    public UUID getClinicId() {
        return clinicId;
    }

    public Long getCustomerUserId() {
        return customerUserId;
    }

    public void setCustomerUserId(Long customerUserId) {
        this.customerUserId = customerUserId;
    }

    public String getContactName() {
        return contactName;
    }

    public void setContactName(String contactName) {
        this.contactName = contactName;
    }

    public String getContactPhone() {
        return contactPhone;
    }

    public void setContactPhone(String contactPhone) {
        this.contactPhone = contactPhone;
    }

    public String getGuestTokenHash() {
        return guestTokenHash;
    }

    public void setGuestTokenHash(String guestTokenHash) {
        this.guestTokenHash = guestTokenHash;
    }

    public OffsetDateTime getLastMessageAt() {
        return lastMessageAt;
    }

    public void setLastMessageAt(OffsetDateTime lastMessageAt) {
        this.lastMessageAt = lastMessageAt;
    }

    public String getLastMessagePreview() {
        return lastMessagePreview;
    }

    public void setLastMessagePreview(String lastMessagePreview) {
        this.lastMessagePreview = lastMessagePreview;
    }

    public ChatSenderKind getLastSenderKind() {
        return lastSenderKind;
    }

    public void setLastSenderKind(ChatSenderKind lastSenderKind) {
        this.lastSenderKind = lastSenderKind;
    }

    public OffsetDateTime getCustomerReadAt() {
        return customerReadAt;
    }

    public void setCustomerReadAt(OffsetDateTime customerReadAt) {
        this.customerReadAt = customerReadAt;
    }

    public OffsetDateTime getStaffReadAt() {
        return staffReadAt;
    }

    public void setStaffReadAt(OffsetDateTime staffReadAt) {
        this.staffReadAt = staffReadAt;
    }
}
