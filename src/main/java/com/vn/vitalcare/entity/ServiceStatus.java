package com.vn.vitalcare.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** Whether a catalogue entry can still be ordered. An inactive one stays on the bills that used it. Lower-case on the wire, upper-case in the database. */
public enum ServiceStatus {

    ACTIVE,

    INACTIVE;

    @JsonValue
    public String getValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static ServiceStatus from(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown service status: " + raw);
        }
    }
}
