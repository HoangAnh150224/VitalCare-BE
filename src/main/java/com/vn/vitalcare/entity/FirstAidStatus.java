package com.vn.vitalcare.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * Where a first-aid article stands in its editorial life (BR-31): written,
 * waiting for a reviewer, approved, out for patients to read, or put away.
 * Lower-case on the wire, upper-case in the database.
 */
public enum FirstAidStatus {

    DRAFT,

    PENDING_REVIEW,

    REVIEWED,

    PUBLISHED,

    ARCHIVED;

    @JsonValue
    public String getValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static FirstAidStatus from(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown first-aid status: " + raw);
        }
    }
}
