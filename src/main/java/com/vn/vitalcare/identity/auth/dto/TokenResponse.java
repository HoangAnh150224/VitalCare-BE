package com.vn.vitalcare.identity.auth.dto;

/**
 * What {@code login}, {@code refresh} and {@code change-password} all answer
 * with: a fresh token pair and the identity it belongs to.
 *
 * @param accessToken  the bearer token sent on every subsequent request
 * @param refreshToken the opaque token used to obtain the next access token.
 *                     Rotated on every use, so the client must replace the one
 *                     it holds with this value or its next refresh will fail
 * @param tokenType    always {@code Bearer}; sent so the client can build the
 *                     header without hardcoding the scheme
 * @param expiresIn    seconds until {@code accessToken} expires, so the client
 *                     can renew ahead of time instead of waiting for a 401
 * @param user         the signed-in identity, so the UI can render the account
 *                     menu straight away. Identity only — what the account may
 *                     do comes from {@code GET /auth/permissions}, which the
 *                     client calls once it holds this token
 */
public record TokenResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn,
        CurrentUserResponse user) {

    public static final String BEARER = "Bearer";

    public static TokenResponse of(String accessToken, String refreshToken,
                                   long expiresIn, CurrentUserResponse user) {
        return new TokenResponse(accessToken, refreshToken, BEARER, expiresIn, user);
    }
}
