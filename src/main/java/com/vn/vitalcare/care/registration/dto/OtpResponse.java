package com.vn.vitalcare.care.registration.dto;

/**
 * A code is on its way.
 *
 * @param expiresIn   seconds the code stays usable, for a countdown
 * @param resendAfter seconds before another code may be asked for, so the
 *                    resend button can stay disabled instead of failing
 */
public record OtpResponse(long expiresIn, long resendAfter) {
}
