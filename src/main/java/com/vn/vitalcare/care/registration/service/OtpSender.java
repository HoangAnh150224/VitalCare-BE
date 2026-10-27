package com.vn.vitalcare.care.registration.service;

import com.vn.vitalcare.care.registration.entity.VerificationPurpose;

/**
 * Delivers a one-time code by SMS to a phone number.
 *
 * <p>An interface so the delivery channel is a bean to swap, not a change to
 * the recovery flow: {@link EsmsOtpSender} when eSMS is configured,
 * {@link LoggingOtpSender} in development.
 */
public interface OtpSender {

    /**
     * @param phone   the number to send to, in E.164 ({@code +84…})
     * @param code    the code in the clear — the only place it exists outside
     *                the message itself
     * @param purpose what the code is for, so the message can say so
     */
    void send(String phone, String code, VerificationPurpose purpose);
}
