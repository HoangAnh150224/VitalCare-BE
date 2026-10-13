package com.vn.vitalcare.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** What a row of a doctor's schedule says about that period: at work, or off. */
public enum ScheduleStatus {
    WORKING("working"),
    LEAVE("leave");

    private final String value;

    ScheduleStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static ScheduleStatus from(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        for (ScheduleStatus status : values()) {
            if (status.value.equalsIgnoreCase(raw) || status.name().equalsIgnoreCase(raw)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown schedule status: " + raw);
    }
}
