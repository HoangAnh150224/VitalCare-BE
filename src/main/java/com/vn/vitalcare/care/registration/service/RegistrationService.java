package com.vn.vitalcare.care.registration.service;

import com.vn.vitalcare.care.registration.dto.RegisterRequest;
import com.vn.vitalcare.identity.auth.dto.TokenResponse;

/**
 * Self-registration: create the account and its neutral customer record, then
 * sign the person in.
 *
 * <p>No one-time code: the number is not proved, by decision. A forgotten
 * password is recovered by a code texted to it; the email is optional.
 *
 * <p>Lives with the care domain rather than identity because what it creates
 * is a customer; it uses identity's services to make the account behind one,
 * and identity knows nothing about it.
 */
public interface RegistrationService {

    /**
     * Creates the account, its neutral customer record and a session — all or
     * nothing. A phone number or email already on another account is refused
     * (409).
     */
    TokenResponse register(RegisterRequest request);
}
