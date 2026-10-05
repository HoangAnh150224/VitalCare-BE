package com.vn.vitalcare.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Where a customer stands in becoming a patient.
 *
 * <p>Every self-registered account starts {@link #NEUTRAL}: it can book, but
 * it is not yet somebody the clinic is responsible for. It becomes a
 * {@link #PATIENT} on an eligible check-in or when a member of staff activates
 * it — never by booking alone. There is no way back: a patient who stops
 * coming is still a patient with a history.
 */
public enum CustomerStatus {

    NEUTRAL("neutral"),

    PATIENT("patient");

    private final String value;

    CustomerStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static CustomerStatus from(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        for (CustomerStatus status : values()) {
            if (status.value.equalsIgnoreCase(raw) || status.name().equalsIgnoreCase(raw)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown customer status: " + raw);
    }
}
