package com.vn.vitalcare.care.chat;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.vn.vitalcare.entity.ChatSenderKind;
import com.vn.vitalcare.entity.Conversation;
import com.vn.vitalcare.entity.Message;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** What the chat endpoints answer. */
public final class ChatResponses {

    private ChatResponses() {
    }

    /**
     * A conversation in the clinic's inbox.
     *
     * @param kind   {@code guest} or {@code customer}
     * @param unread messages from the other side nobody at the clinic has read
     */
    public record Inbox(
            Long id,
            UUID clinicId,
            String clinicName,
            String kind,
            String contactName,
            String contactPhone,
            String status,
            OffsetDateTime lastMessageAt,
            String lastMessagePreview,
            ChatSenderKind lastSenderKind,
            long unread,
            @JsonProperty("_can")
            Map<String, Boolean> can) {

        static Inbox from(Conversation c, long unread, Map<String, Boolean> can) {
            return new Inbox(c.getId(), c.getClinicId(), c.getClinic() == null ? null : c.getClinic().getClinicName(),
                    c.isGuest() ? "guest" : "customer", c.getContactName(), c.getContactPhone(), c.getStatus(),
                    c.getLastMessageAt(), c.getLastMessagePreview(), c.getLastSenderKind(), unread, can);
        }
    }

    /**
     * A conversation as the customer or guest sees it: with which clinic, and
     * how many of the clinic's messages they have not read.
     */
    public record Own(
            Long id,
            UUID clinicId,
            String clinicName,
            String clinicPhone,
            String status,
            OffsetDateTime lastMessageAt,
            String lastMessagePreview,
            ChatSenderKind lastSenderKind,
            long unread) {

        static Own from(Conversation c, long unread) {
            return new Own(c.getId(), c.getClinicId(), c.getClinic() == null ? null : c.getClinic().getClinicName(),
                    c.getClinic() == null ? null : c.getClinic().getContactPhone(), c.getStatus(),
                    c.getLastMessageAt(), c.getLastMessagePreview(), c.getLastSenderKind(), unread);
        }
    }

    /** What a guest gets on starting: the conversation, and the token that is now its only key to it. */
    public record GuestStarted(Own conversation, String token) {
    }

    /**
     * One message, from the reader's side.
     *
     * @param mine  written by the side reading it
     * @param read  for one's own message: whether the other side has opened the conversation since
     * @param image whether it is an image, fetched from the attachment endpoint by its id
     */
    public record Line(
            Long id,
            ChatSenderKind senderKind,
            String senderName,
            boolean mine,
            String text,
            boolean image,
            OffsetDateTime sentAt,
            boolean read) {
    }

    /** A conversation's latest messages, oldest first, with the conversation itself. */
    public record Thread(Object conversation, List<Line> messages) {
    }

    static String preview(Message message) {
        if (Message.IMAGE.equals(message.getMessageType())) {
            return "[Ảnh]";
        }
        String text = message.getContent() == null ? "" : message.getContent().strip().replaceAll("\\s+", " ");
        return text.length() > 200 ? text.substring(0, 197) + "..." : text;
    }
}
