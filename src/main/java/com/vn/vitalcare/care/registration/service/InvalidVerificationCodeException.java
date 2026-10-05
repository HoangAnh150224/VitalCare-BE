package com.vn.vitalcare.care.registration.service;

import com.vn.vitalcare.share.exception.FieldValidationException;

/**
 * The one-time code was wrong, expired, already used or burned.
 *
 * <p>One exception and one message for all four, so a failed attempt says
 * nothing about which it was — in particular not that a guess was close to a
 * code that is still live. Answered as a field error on {@code otp}, which is
 * where the person has to act.
 */
public class InvalidVerificationCodeException extends FieldValidationException {

    public InvalidVerificationCodeException() {
        super("otp", "The code is incorrect or has expired");
    }
}
