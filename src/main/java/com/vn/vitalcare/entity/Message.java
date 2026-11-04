package com.vn.vitalcare.entity;

import com.vn.vitalcare.share.data.BaseEntity;
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
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * One message in a {@link Conversation}: text, or an image held in the file
 * store under {@link #attachmentKey}.
 *
 * <p>The author is a bare {@code users.id} (null for a guest), for the reason
 * {@code BaseEntity} gives about entities of the identity domain.
 */
@Entity
@Table(name = "message")
public class Message extends BaseEntity {

    public static final String TEXT = "TEXT";
    public static final String IMAGE = "IMAGE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false)
    private Conversation conversation;

    @Column(name = "sender_id")
    private Long senderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "sender_kind", length = 16)
    private ChatSenderKind senderKind;

    @Column(name = "content", columnDefinition = "text")
    private String content;

    /** {@link #TEXT} or {@link #IMAGE}. */
    @Column(name = "message_type")
    private String messageType = TEXT;

    @Column(name = "sent_at")
    private OffsetDateTime sentAt;

    @Column(name = "edited_at")
    private OffsetDateTime editedAt;

    @Column(name = "attachment_key")
    private String attachmentKey;

    @Column(name = "attachment_type", length = 64)
    private String attachmentType;

    @Column(name = "attachment_size")
    private Long attachmentSize;

    // deletedAt comes from BaseEntity: the chat's own soft delete and the
    // base class's are the same thing on the same column, and the base one
    // records who did it as well. Use markDeleted(actorId, when).

    public Message() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Conversation getConversation() {
        return conversation;
    }

    public void setConversation(Conversation conversation) {
        this.conversation = conversation;
    }

    public Long getSenderId() {
        return senderId;
    }

    public void setSenderId(Long senderId) {
        this.senderId = senderId;
    }

    public ChatSenderKind getSenderKind() {
        return senderKind;
    }

    public void setSenderKind(ChatSenderKind senderKind) {
        this.senderKind = senderKind;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getMessageType() {
        return messageType;
    }

    public void setMessageType(String messageType) {
        this.messageType = messageType;
    }

    public OffsetDateTime getSentAt() {
        return sentAt;
    }

    public void setSentAt(OffsetDateTime sentAt) {
        this.sentAt = sentAt;
    }

    public OffsetDateTime getEditedAt() {
        return editedAt;
    }

    public void setEditedAt(OffsetDateTime editedAt) {
        this.editedAt = editedAt;
    }

    public String getAttachmentKey() {
        return attachmentKey;
    }

    public void setAttachmentKey(String attachmentKey) {
        this.attachmentKey = attachmentKey;
    }

    public String getAttachmentType() {
        return attachmentType;
    }

    public void setAttachmentType(String attachmentType) {
        this.attachmentType = attachmentType;
    }

    public Long getAttachmentSize() {
        return attachmentSize;
    }

    public void setAttachmentSize(Long attachmentSize) {
        this.attachmentSize = attachmentSize;
    }
}
