package com.vn.vitalcare.identity.user.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Whether an account may sign in, and why not if it may not.
 *
 * <p>Only {@link #ACTIVE} can authenticate. The distinction between the other
 * two is deliberate and visible to the person signing in: {@code INACTIVE} is
 * an administrative decision, {@code LOCKED} is a security one.
 */
public enum UserStatus {

    /** The account can sign in. */
    ACTIVE("active"),

    /** Deliberately switched off — someone who has left, or not started yet. */
    INACTIVE("inactive"),

    /** Suspended, e.g. after suspicious activity. Needs an admin to release. */
    LOCKED("locked");

    private final String value;

    UserStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static UserStatus from(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        for (UserStatus status : values()) {
            if (status.value.equalsIgnoreCase(raw) || status.name().equalsIgnoreCase(raw)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown user status: " + raw);
    }
}
