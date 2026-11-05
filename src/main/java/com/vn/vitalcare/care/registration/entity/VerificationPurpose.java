package com.vn.vitalcare.care.registration.entity;

/**
 * What a one-time code proves a number for. A code sent for one purpose is
 * never accepted for another.
 */
public enum VerificationPurpose {

    /** Proving control of this number before creating a self-registered account. */
    REGISTER,

    /** Setting a new password on the account that signs in with this number. */
    RESET_PASSWORD
}
