package com.vn.vitalcare.care.registration.service;

import com.vn.vitalcare.care.registration.entity.VerificationPurpose;

/**
 * Delivers a one-time code to an email address.
 *
 * <p>An interface so the delivery channel is a bean to swap, not a change to
 * the recovery flow: {@link EmailOtpSender} when mail is configured,
 * {@link LoggingOtpSender} in development.
 */
public interface OtpSender {

    /**
     * @param email   where to send the code
     * @param code    the code in the clear — the only place it exists outside
     *                the message itself
     * @param purpose what the code is for, so the message can say so
     */
    void send(String email, String code, VerificationPurpose purpose);
}
