package com.vn.vitalcare.care.registration.service;

import com.vn.vitalcare.care.registration.dto.OtpResponse;
import com.vn.vitalcare.care.registration.dto.PasswordResetRequest;

/**
 * Recovering a forgotten password: the person types the phone number the
 * account signs in with, receives a code on it by SMS, and chooses
 * a new password with it.
 */
public interface PasswordResetService {

    /**
     * Texts a recovery code to the number, if an account that may sign in
     * uses it.
     *
     * <p>Answers the same either way — same reply, same cooldown, same hourly
     * cap — so the form cannot be used to learn which numbers have accounts.
     * Only whether a message goes out differs.
     */
    OtpResponse requestCode(String rawPhone);

    /**
     * Checks the code without spending it, so the form can confirm it before
     * asking for the new password. A wrong guess still counts toward burning
     * the code.
     *
     * @throws InvalidVerificationCodeException wrong, expired, spent or burned
     */
    void checkCode(String rawPhone, String otp);

    /**
     * Checks the code and, if it holds, sets the new password and ends every
     * session open under the account. Does not sign in: the person signs in
     * with the password they just chose.
     *
     * <p>A wrong code commits its attempt count, so guesses burn the code.
     */
    void reset(PasswordResetRequest request);
}
