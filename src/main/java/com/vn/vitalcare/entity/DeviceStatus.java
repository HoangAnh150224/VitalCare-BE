package com.vn.vitalcare.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Where a monitoring device is.
 *
 * <p>{@link #ASSIGNED} is set and cleared only by handing a device to a
 * patient and taking it back; nobody sets it by hand, so the status and the
 * open assignment cannot disagree.
 */
public enum DeviceStatus {

    /** On the shelf, ready to hand out. */
    AVAILABLE("available"),

    /** On a patient. */
    ASSIGNED("assigned"),

    /** Out of use for repair or calibration. */
    MAINTENANCE("maintenance"),

    /** Out of use for good; kept for its history. */
    RETIRED("retired");

    private final String value;

    DeviceStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static DeviceStatus from(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        for (DeviceStatus status : values()) {
            if (status.value.equalsIgnoreCase(raw) || status.name().equalsIgnoreCase(raw)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown device status: " + raw);
    }
}
