package com.vn.vitalcare.identity.user.controller;

import com.vn.vitalcare.identity.user.dto.SetPasswordRequest;
import com.vn.vitalcare.identity.user.dto.UserPatchRequest;
import com.vn.vitalcare.identity.user.dto.UserRequest;
import com.vn.vitalcare.identity.user.dto.UserResponse;
import com.vn.vitalcare.identity.user.entity.User;
import com.vn.vitalcare.identity.user.service.UserService;
import com.vn.vitalcare.share.security.Permissions;
import com.vn.vitalcare.share.web.ListParams;
import com.vn.vitalcare.share.web.ListResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/users}.
 *
 * <p>Setting a password is its own endpoint rather than a field on the update
 * payload. Restoring access to an account is a different act from editing a
 * profile, and keeping it separate is what lets it end every session open
 * under that account without a profile edit doing the same by surprise.
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService service;

    public UserController(UserService service) {
        this.service = service;
    }

    /**
     * Serves both {@code getList} and {@code getMany}.
     *
     * <p>They differ only in the query string — {@code getMany} sends
     * {@code ids[n]} and no window — so they share one endpoint, with the id
     * lookup answered unpaged.
     */
    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.USERS_READ + "')")
    public ResponseEntity<List<UserResponse>> list(@RequestParam MultiValueMap<String, String> query) {
        ListParams params = new ListParams(query);

        List<Long> ids = params.ids();
        if (!ids.isEmpty()) {
            List<UserResponse> rows = service.getMany(ids).stream()
                    .map(UserResponse::from)
                    .toList();
            return ListResponse.of(rows, rows.size());
        }

        Page<User> page = service.list(params);
        return ListResponse.of(page.map(UserResponse::from).getContent(), page.getTotalElements());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.USERS_READ + "')")
    public UserResponse get(@PathVariable Long id) {
        return UserResponse.from(service.get(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + Permissions.USERS_WRITE + "')")
    public ResponseEntity<UserResponse> create(@Valid @RequestBody UserRequest request) {
        User created = service.create(request);
        return ResponseEntity
                .created(URI.create("/api/users/" + created.getId()))
                .body(UserResponse.from(created));
    }

    /**
     * {@code PATCH} is what a client normally sends; {@code PUT} is mapped to
     * the same handler so the resource is also usable from a client that only
     * speaks full replacement.
     */
    @RequestMapping(value = "/{id}", method = {RequestMethod.PATCH, RequestMethod.PUT})
    @PreAuthorize("hasAuthority('" + Permissions.USERS_WRITE + "')")
    public UserResponse update(@PathVariable Long id, @Valid @RequestBody UserPatchRequest request) {
        return UserResponse.from(service.update(id, request));
    }

    /**
     * Sets a user's password on their behalf and signs out every session open
     * under the account.
     *
     * <p>Answers with the user rather than the new password, which the caller
     * already knows and nothing else should ever see again.
     */
    @PostMapping("/{id}/password")
    @PreAuthorize("hasAuthority('" + Permissions.USERS_WRITE + "')")
    public UserResponse setPassword(@PathVariable Long id, @Valid @RequestBody SetPasswordRequest request) {
        return UserResponse.from(service.setPassword(id, request.password()));
    }

    /** Echoes the deleted row. */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.USERS_DELETE + "')")
    public UserResponse delete(@PathVariable Long id) {
        return UserResponse.from(service.delete(id));
    }
}
