package com.vn.vitalcare.entity;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * What a bill is for (BF-02, BF-03): the examination fee of a visit, paid
 * before the patient queues, or one ordered service, paid before it is done.
 * Bills issued before the split carry both and count as {@link #EXAM}.
 */
public enum InvoiceKind {

    EXAM,

    SERVICE;

    @JsonValue
    public String getValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
