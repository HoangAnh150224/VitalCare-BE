package com.vn.vitalcare.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * What kind of staff somebody is. Each maps to the system role their account
 * receives, so what they may do follows from what they are.
 *
 * <p>The front desk is staff too: a receptionist works at one clinic, and
 * that is what limits the appointments, customers and devices they see.
 */
public enum StaffType {

    DOCTOR("doctor", "DOCTOR"),

    NURSE("nurse", "NURSE"),

    RECEPTIONIST("receptionist", "MANAGER");

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

    /** The system role, seeded by 002 and 017, that an account of this kind holds. */
    public String roleCode() {
        return roleCode;
    }

    /** Whether somebody of this kind can be on a patient's care team. The front desk cannot. */
    public boolean isClinical() {
        return this != RECEPTIONIST;
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
