package com.vn.vitalcare.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Where a registered visit stands.
 *
 * <p>Registered, then checked in at the desk — waiting in the doctor's queue —
 * then called into an examination room, then completed. The two other
 * ways out of {@link #SCHEDULED} are final: a cancelled visit and one the
 * patient never came to both stay as they are — the patient registers again.
 */
public enum AppointmentStatus {

    SCHEDULED("scheduled"),

    CHECKED_IN("checked_in"),

    /** Called into an examination room and being seen; see {@code ExamRoom}. */
    IN_EXAM("in_exam"),

    COMPLETED("completed"),

    CANCELLED("cancelled"),

    NO_SHOW("no_show");

    private final String value;

    AppointmentStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static AppointmentStatus from(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        for (AppointmentStatus status : values()) {
            if (status.value.equalsIgnoreCase(raw) || status.name().equalsIgnoreCase(raw)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown appointment status: " + raw);
    }
}
