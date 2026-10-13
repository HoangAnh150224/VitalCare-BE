package com.vn.vitalcare.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Where a clinical encounter stands. Completing it starts the 24 hours after which it locks (SRS BR-27). */
public enum EncounterStatus {

    IN_PROGRESS("in_progress"),

    COMPLETED("completed");

    private final String value;

    EncounterStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static EncounterStatus from(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        for (EncounterStatus status : values()) {
            if (status.value.equalsIgnoreCase(raw) || status.name().equalsIgnoreCase(raw)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown encounter status: " + raw);
    }
}
