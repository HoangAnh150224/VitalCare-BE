package com.vn.vitalcare.care.registration.service;

import com.vn.vitalcare.care.registration.dto.OtpResponse;
import com.vn.vitalcare.care.registration.dto.RegisterRequest;
import com.vn.vitalcare.care.registration.repository.PhoneVerificationRepository;
import com.vn.vitalcare.identity.auth.dto.TokenResponse;

/**
 * Self-registration: prove the phone number, then create the account and its
 * neutral customer record, then sign the person in.
 *
 * <p>Lives with the care domain rather than identity because what it creates
 * is a customer; it uses identity's services to make the account behind one,
 * and identity knows nothing about it.
 */
public interface RegistrationService {

    /**
     * Sends a registration code to a number.
     *
     * <p>A number that already has an account is refused outright. That does
     * tell the caller the number is registered — unavoidable on a sign-up form,
     * which has to say why it will not proceed — and nothing here limits how
     * fast that question can be asked: the resend limits below only meter
     * codes actually sent. Bounding it needs a per-client rate limit in front
     * of the endpoint, which this application does not have yet.
     *
     * <p>The newest code row is locked while the limits are checked, so two
     * requests in parallel cannot both slip under the cooldown. The very first
     * request for a number has no row to lock; two of those racing can send
     * two codes, of which only the newer is ever accepted.
     */
    OtpResponse requestCode(String rawPhone);

    /**
     * Checks the code and, if it holds, creates the account, its neutral
     * customer record and a session — all or nothing.
     *
     * <p>A wrong code is the one failure that must <em>not</em> roll back: the
     * attempt it records is what burns the code after the configured number
     * of guesses. Nothing else has been written by then, so letting that
     * transaction commit keeps only the counter.
     *
     * <p>The code row is read under a lock, so guesses sent in parallel queue
     * on it and each one is counted; see
     * {@link PhoneVerificationRepository#findLatestForUpdate}.
     */
    TokenResponse register(RegisterRequest request);
}
