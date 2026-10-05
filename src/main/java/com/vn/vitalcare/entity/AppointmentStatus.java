package com.vn.vitalcare.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * An appointment's state.
 *
 * <p>Both ways out of {@link #SCHEDULED} are final: a checked-in appointment
 * cannot be cancelled after the fact, and a cancelled one cannot be checked in
 * — the customer books again. Completion and no-show belong to the encounter
 * workflow and are added with it.
 */
public enum AppointmentStatus {

    SCHEDULED("scheduled"),

    CHECKED_IN("checked_in"),

    CANCELLED("cancelled");

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
