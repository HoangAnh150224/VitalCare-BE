package com.vn.vitalcare.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * What kind of clinical staff somebody is. Each maps to the system role their
 * account receives, so what they may do follows from what they are.
 */
public enum StaffType {

    DOCTOR("doctor", "DOCTOR"),

    NURSE("nurse", "NURSE");

    private final String value;
    private final String roleCode;

    StaffType(String value, String roleCode) {
        this.value = value;
        this.roleCode = roleCode;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    /** The system role, seeded by 017, that an account of this kind holds. */
    public String roleCode() {
        return roleCode;
    }

    @JsonCreator
    public static StaffType from(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        for (StaffType type : values()) {
            if (type.value.equalsIgnoreCase(raw) || type.name().equalsIgnoreCase(raw)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown staff type: " + raw);
    }
}
