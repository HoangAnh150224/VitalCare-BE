package com.vn.vitalcare.identity.auth.controller;

import com.vn.vitalcare.identity.auth.dto.AuthoritiesResponse;
import com.vn.vitalcare.identity.auth.dto.ChangePasswordRequest;
import com.vn.vitalcare.identity.auth.dto.CurrentUserResponse;
import com.vn.vitalcare.identity.auth.dto.LoginRequest;
import com.vn.vitalcare.identity.auth.dto.RefreshRequest;
import com.vn.vitalcare.identity.auth.dto.TokenResponse;
import com.vn.vitalcare.identity.auth.service.AuthService;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/auth} — signing in and everything that follows from it.
 *
 * <p>{@code login}, {@code refresh} and {@code logout} are open in
 * {@code SecurityConfig}, because they are precisely the three things you must
 * be able to do without a valid access token. {@code me} and
 * {@code change-password} need one, and no permission beyond that: every
 * account may read and change its own.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService service;

    public AuthController(AuthService service) {
        this.service = service;
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return service.login(request);
    }

    /**
     * Trades a refresh token for a new pair.
     *
     * <p>The token sent here is burned whether or not the call succeeds, so the
     * client has to store what comes back — see {@code TokenResponse}.
     */
    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return service.refresh(request.refreshToken());
    }

    /**
     * Ends the session and answers 200 whatever it was given.
     *
     * <p>A body rather than {@code 204}: a client that parses the response of
     * every call it makes would otherwise read an empty one as a failure.
     */
    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(@RequestBody(required = false) RefreshRequest request) {
        service.logout(request == null ? null : request.refreshToken());
        return ResponseEntity.ok(Map.of("success", true));
    }

    /** Who the caller is, re-read from the database rather than from the token. */
    @GetMapping("/me")
    public CurrentUserResponse me() {
        return service.me();
    }

    /**
     * What the caller may do — their role codes and every permission those
     * roles grant.
     *
     * <p>No permission of its own: every account may read its own grants, and
     * an account that could not would have no way to render a menu.
     */
    @GetMapping("/permissions")
    public AuthoritiesResponse permissions() {
        return service.authorities();
    }

    /**
     * Changes the caller's own password.
     *
     * <p>Answers with a fresh token pair, because the change ends every other
     * session under the account — including, without this, the one that asked
     * for it.
     */
    @PostMapping("/change-password")
    public TokenResponse changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        return service.changePassword(request);
    }
}
