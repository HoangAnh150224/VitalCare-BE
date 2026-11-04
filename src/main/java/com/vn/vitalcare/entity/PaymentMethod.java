package com.vn.vitalcare.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** How a payment was made: at the counter in cash or by transfer, or online through VNPay. Lower-case on the wire, upper-case in the database. */
public enum PaymentMethod {

    CASH,

    BANK_TRANSFER,

    VNPAY;

    @JsonValue
    public String getValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static PaymentMethod from(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown payment method: " + raw);
        }
    }
}
