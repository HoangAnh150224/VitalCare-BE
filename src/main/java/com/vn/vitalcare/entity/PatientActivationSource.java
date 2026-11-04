package com.vn.vitalcare.entity;

import com.fasterxml.jackson.annotation.JsonValue;

/** How a customer became a patient — recorded so the activation can be accounted for. */
public enum PatientActivationSource {

    /** Checked in for an appointment while still neutral. */
    CHECK_IN("check_in"),

    /** Activated by a member of staff holding {@code customers:activate}. */
    MANUAL("manual");

    private final String value;

    PatientActivationSource(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}
