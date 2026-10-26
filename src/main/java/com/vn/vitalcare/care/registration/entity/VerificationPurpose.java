package com.vn.vitalcare.care.registration.entity;

/**
 * What a one-time code proves a number for. A code sent for one purpose is
 * never accepted for another.
 */
public enum VerificationPurpose {

    /**
     * Self-registration of a new account on this number. No longer issued —
     * registration takes no code — but kept: older rows hold it.
     */
    REGISTER,

    /** Setting a new password on the account that signs in with this number. */
    RESET_PASSWORD
}
