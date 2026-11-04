package com.vn.vitalcare.care.customer.controller;

import com.vn.vitalcare.care.customer.dto.ProfileRequest;
import com.vn.vitalcare.care.customer.dto.ProfileResponse;
import com.vn.vitalcare.care.customer.service.CustomerService;
import com.vn.vitalcare.entity.Customer;
import com.vn.vitalcare.share.security.Permissions;
import com.vn.vitalcare.share.web.ListResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/my_profiles} — the patient profiles under the signed-in account:
 * the owner's own, and the relatives they register visits for.
 *
 * <p>Whose profiles they are comes from the access token, so nothing a client
 * sends reaches another account's. No delete: a profile may already have
 * visits and records hanging from it.
 */
@RestController
@RequestMapping("/api/my_profiles")
public class MyProfileController {

    private final CustomerService service;

    public MyProfileController(CustomerService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.MY_PROFILES_READ + "')")
    public ResponseEntity<List<ProfileResponse>> list() {
        List<ProfileResponse> rows = service.profilesOf(service.getForCurrentUser()).stream()
                .map(ProfileResponse::from).toList();
        return ListResponse.of(rows, rows.size());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.MY_PROFILES_READ + "')")
    public ProfileResponse get(@PathVariable Long id) {
        return ProfileResponse.from(service.ownProfile(service.getForCurrentUser(), id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + Permissions.MY_PROFILES_WRITE + "')")
    public ResponseEntity<ProfileResponse> create(@Valid @RequestBody ProfileRequest request) {
        Customer created = service.createDependent(service.getForCurrentUser(), request);
        return ResponseEntity
                .created(URI.create("/api/my_profiles/" + created.getId()))
                .body(ProfileResponse.from(created));
    }

    @RequestMapping(value = "/{id}", method = {RequestMethod.PATCH, RequestMethod.PUT})
    @PreAuthorize("hasAuthority('" + Permissions.MY_PROFILES_WRITE + "')")
    public ProfileResponse update(@PathVariable Long id, @Valid @RequestBody ProfileRequest request) {
        return ProfileResponse.from(service.updateOwnProfile(service.getForCurrentUser(), id, request));
    }
}
