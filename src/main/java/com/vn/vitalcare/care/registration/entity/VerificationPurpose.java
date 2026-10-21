package com.vn.vitalcare.care.registration.entity;

/**
 * What a one-time code proves a number for. A code sent for one purpose is
 * never accepted for another.
 */
public enum VerificationPurpose {

    /** Self-registration of a new account on this number. */
    REGISTER,

    /** Setting a new password on the account that signs in with this number. */
    RESET_PASSWORD
}
