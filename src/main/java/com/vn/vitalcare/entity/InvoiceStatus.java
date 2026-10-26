package com.vn.vitalcare.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** Whether a bill has been settled. A failed online payment leaves it unpaid; see PaymentStatus. Lower-case on the wire, upper-case in the database. */
public enum InvoiceStatus {

    UNPAID,

    PAID,

    CANCELLED;

    @JsonValue
    public String getValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static InvoiceStatus from(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown invoice status: " + raw);
        }
    }
}
