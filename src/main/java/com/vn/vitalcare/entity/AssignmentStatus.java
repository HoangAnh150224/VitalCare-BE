package com.vn.vitalcare.entity;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * An assignment — somebody in a patient's care team, a device on a patient —
 * is open until it is ended. Ending is final; assigning again is a new row,
 * so the history reads as it happened.
 */
public enum AssignmentStatus {

    ACTIVE("active"),

    ENDED("ended");

    private final String value;

    AssignmentStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}
