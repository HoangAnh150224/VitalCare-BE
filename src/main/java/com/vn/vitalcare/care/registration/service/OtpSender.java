package com.vn.vitalcare.care.registration.service;

/**
 * Delivers a one-time code to a phone.
 *
 * <p>An interface so the delivery channel is a bean to swap, not a change to
 * the registration flow: {@link LoggingOtpSender} today, an SMS provider when
 * there is one.
 */
public interface OtpSender {

    /**
     * @param phone the number, normalised to E.164
     * @param code  the code in the clear — the only place it exists outside
     *              the message itself
     */
    void send(String phone, String code);
}
