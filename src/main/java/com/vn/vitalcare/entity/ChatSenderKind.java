package com.vn.vitalcare.entity;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** Who wrote a chat message: a guest not signed in, a customer's account, or the clinic's staff. */
public enum ChatSenderKind {

    GUEST,

    CUSTOMER,

    STAFF;

    @JsonValue
    public String getValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
