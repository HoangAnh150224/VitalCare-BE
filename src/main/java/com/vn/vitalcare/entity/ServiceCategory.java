package com.vn.vitalcare.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** What kind of service a catalogue entry is. CONSULTATION is the examination fee itself. Lower-case on the wire, upper-case in the database. */
public enum ServiceCategory {

    CONSULTATION,

    LAB,

    IMAGING,

    PROCEDURE;

    @JsonValue
    public String getValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static ServiceCategory from(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown service category: " + raw);
        }
    }
}
