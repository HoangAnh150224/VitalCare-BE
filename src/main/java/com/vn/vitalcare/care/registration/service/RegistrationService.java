package com.vn.vitalcare.care.registration.service;

import com.vn.vitalcare.care.registration.dto.OtpResponse;
import com.vn.vitalcare.care.registration.dto.ProfileMatch;
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

    /**
     * Sends a registration code to a validated number, subject to the OTP
     * limits; a number that already has an account is refused first (UC-04 D2).
     */
    OtpResponse requestCode(String rawPhone);

    /**
     * Whether the code is right, without spending it, and — since it is — the
     * profiles with that number and no account, masked (UC-04 steps 9–10).
     * A wrong code counts as a wrong guess, as in registering.
     */
    java.util.List<ProfileMatch> checkCode(String rawPhone, String otp);

    /** Creates the account, links the claimed profile (or creates a guest one) and the relatives sharing the number. */
    TokenResponse register(RegisterRequest request);
}
