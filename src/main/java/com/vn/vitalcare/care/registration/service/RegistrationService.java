package com.vn.vitalcare.care.registration.service;

import com.vn.vitalcare.care.registration.dto.OtpResponse;
import com.vn.vitalcare.care.registration.dto.RegisterRequest;
import com.vn.vitalcare.identity.auth.dto.TokenResponse;

/**
 * Self-registration: prove control of the phone by SMS before creating the
 * account and its neutral customer record, then sign the person in.
 *
 * <p>Lives with the care domain rather than identity because what it creates
 * is a customer; it uses identity's services to make the account behind one,
 * and identity knows nothing about it.
 */
public interface RegistrationService {

    /** Sends a registration code to a validated number, subject to the OTP limits. */
    OtpResponse requestCode(String rawPhone);

    /** Creates the account, links a matching walk-in profile or creates a guest profile, then signs in. */
    TokenResponse register(RegisterRequest request);
}
