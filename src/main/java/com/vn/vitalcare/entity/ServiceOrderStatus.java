package com.vn.vitalcare.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** Where an ordered service stands: ordered, being done, result in, seen by the doctor; or cancelled. Lower-case on the wire, upper-case in the database. */
public enum ServiceOrderStatus {

    ORDERED,

    IN_PROGRESS,

    RESULT_READY,

    REVIEWED,

    CANCELLED;

    @JsonValue
    public String getValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static ServiceOrderStatus from(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown service order status: " + raw);
        }
    }
}
