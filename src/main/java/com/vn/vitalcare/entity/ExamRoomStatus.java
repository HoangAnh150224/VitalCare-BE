package com.vn.vitalcare.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Whether an examination room can take the next patient.
 *
 * <p>Free, with somebody in it, or paused — a room under maintenance or a
 * doctor on a break. Only a free room is ever given a patient.
 */
public enum ExamRoomStatus {

    AVAILABLE("available"),

    OCCUPIED("occupied"),

    PAUSED("paused");

    private final String value;

    ExamRoomStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static ExamRoomStatus from(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        for (ExamRoomStatus status : values()) {
            if (status.value.equalsIgnoreCase(raw) || status.name().equalsIgnoreCase(raw)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown exam room status: " + raw);
    }
}
