package com.vn.vitalcare.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Whether a member of staff is still working here. Leaving is INACTIVE, never a delete. */
public enum EmployeeStatus {

    ACTIVE("active"),

    INACTIVE("inactive");

    private final String value;

    EmployeeStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static EmployeeStatus from(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        for (EmployeeStatus status : values()) {
            if (status.value.equalsIgnoreCase(raw) || status.name().equalsIgnoreCase(raw)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown staff status: " + raw);
    }
}
