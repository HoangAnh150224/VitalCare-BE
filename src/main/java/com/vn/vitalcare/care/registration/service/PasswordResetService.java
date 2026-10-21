package com.vn.vitalcare.care.registration.service;

import com.vn.vitalcare.care.registration.dto.OtpResponse;
import com.vn.vitalcare.care.registration.dto.PasswordResetRequest;

/**
 * Recovering a forgotten password: prove the phone number the account signs
 * in with, then choose a new password.
 *
 * <p>The SRS draws this as a link sent by email. Accounts here sign in with a
 * phone number and a self-registered one has no email, so the proof is the
 * same one-time code registration uses, sent to that number.
 */
public interface PasswordResetService {

    /**
     * Sends a recovery code to a number, if an account that may sign in uses it.
     *
     * <p>Answers the same either way — same reply, same cooldown, same hourly
     * cap — so the form cannot be used to learn which numbers have accounts.
     * Only whether a message goes out differs.
     */
    OtpResponse requestCode(String rawPhone);

    /**
     * Checks the code and, if it holds, sets the new password and ends every
     * session open under the account. Does not sign in: the person signs in
     * with the password they just chose.
     *
     * <p>A wrong code commits its attempt count, as in registration, so
     * guesses burn the code.
     */
    void reset(PasswordResetRequest request);
}
