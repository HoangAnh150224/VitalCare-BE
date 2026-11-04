package com.vn.vitalcare.care.chat;

import com.vn.vitalcare.entity.ChatSenderKind;
import com.vn.vitalcare.entity.Conversation;
import com.vn.vitalcare.share.data.BaseEntitySpecifications;
import com.vn.vitalcare.share.phone.PhoneNumbers;
import com.vn.vitalcare.share.web.ListParams;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/** The inbox's filters over the parsed query string; deleted rows are excluded. */
final class ConversationSpecifications {

    private ConversationSpecifications() {
    }

    static Specification<Conversation> from(ListParams params) {
        List<Specification<Conversation>> specs = new ArrayList<>();
        specs.add(BaseEntitySpecifications.notDeleted());
        specs.add((root, query, cb) -> cb.isNotNull(root.get("lastMessageAt")));

        for (ListParams.Criterion criterion : params.filters()) {
            String value = criterion.value() == null ? "" : criterion.value().trim().toLowerCase(Locale.ROOT);
            try {
                switch (criterion.field()) {
                    case "status" -> specs.add((root, query, cb) -> cb.equal(root.get("status"), value.toUpperCase(Locale.ROOT)));
                    case "kind" -> specs.add((root, query, cb) -> "guest".equals(value)
                            ? cb.isNull(root.get("customerUserId"))
                            : cb.isNotNull(root.get("customerUserId")));
                    case "read" -> specs.add("unread".equals(value) ? waitingOnStaff() : Specification.not(waitingOnStaff()));
                    case "clinicId" -> {
                        UUID clinicId = UUID.fromString(value);
                        specs.add((root, query, cb) -> cb.equal(root.get("clinicId"), clinicId));
                    }
                    default -> {
                    }
                }
            } catch (IllegalArgumentException e) {
                specs.add((root, query, cb) -> cb.disjunction());
            }
        }

        // The name they gave, or the end of their phone number however it was typed.
        params.search().ifPresent(term -> specs.add((root, query, cb) -> {
            String tail = PhoneNumbers.searchTail(term);
            return !tail.matches("\\d{3,}")
                    ? cb.like(cb.lower(root.get("contactName")), ListParams.likePattern(term))
                    : cb.or(cb.like(cb.lower(root.get("contactName")), ListParams.likePattern(term)),
                            cb.like(root.get("contactPhone"), "%" + tail + "%"));
        }));
        return Specification.allOf(specs);
    }

    /** The other side wrote last and nobody at the clinic has opened it since. */
    static Specification<Conversation> waitingOnStaff() {
        return (root, query, cb) -> cb.and(
                cb.notEqual(root.get("lastSenderKind"), ChatSenderKind.STAFF),
                cb.or(cb.isNull(root.get("staffReadAt")),
                        cb.greaterThan(root.get("lastMessageAt"), root.get("staffReadAt"))));
    }
}
