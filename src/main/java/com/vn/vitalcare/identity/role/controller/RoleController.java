package com.vn.vitalcare.identity.role.controller;

import com.vn.vitalcare.identity.role.dto.RolePatchRequest;
import com.vn.vitalcare.identity.role.dto.RoleRequest;
import com.vn.vitalcare.identity.role.dto.RoleResponse;
import com.vn.vitalcare.identity.role.entity.Role;
import com.vn.vitalcare.identity.role.service.RoleService;
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
 * {@code /api/roles}.
 *
 * <p>Each endpoint declares the permission it needs on the method rather than
 * in the security configuration, so the rule and the thing it guards cannot
 * end up in different files at different times.
 */
@RestController
@RequestMapping("/api/roles")
public class RoleController {

    private final RoleService service;

    public RoleController(RoleService service) {
        this.service = service;
    }

    /** Serves both {@code getList} and {@code getMany}, as elsewhere. */
    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.ROLES_READ + "')")
    public ResponseEntity<List<RoleResponse>> list(@RequestParam MultiValueMap<String, String> query) {
        ListParams params = new ListParams(query);

        List<Long> ids = params.ids();
        if (!ids.isEmpty()) {
            List<RoleResponse> rows = service.getMany(ids).stream()
                    .map(RoleResponse::from)
                    .toList();
            return ListResponse.of(rows, rows.size());
        }

        Page<Role> page = service.list(params);
        return ListResponse.of(page.map(RoleResponse::from).getContent(), page.getTotalElements());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.ROLES_READ + "')")
    public RoleResponse get(@PathVariable Long id) {
        return RoleResponse.from(service.get(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + Permissions.ROLES_WRITE + "')")
    public ResponseEntity<RoleResponse> create(@Valid @RequestBody RoleRequest request) {
        Role created = service.create(request);
        return ResponseEntity
                .created(URI.create("/api/roles/" + created.getId()))
                .body(RoleResponse.from(created));
    }

    /**
     * {@code PATCH} is what a client normally sends; {@code PUT} is mapped to
     * the same handler so the resource is also usable from a client that only
     * speaks full replacement.
     */
    @RequestMapping(value = "/{id}", method = {RequestMethod.PATCH, RequestMethod.PUT})
    @PreAuthorize("hasAuthority('" + Permissions.ROLES_WRITE + "')")
    public RoleResponse update(@PathVariable Long id, @Valid @RequestBody RolePatchRequest request) {
        return RoleResponse.from(service.update(id, request));
    }

    /** Echoes the deleted row. */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.ROLES_DELETE + "')")
    public RoleResponse delete(@PathVariable Long id) {
        return RoleResponse.from(service.delete(id));
    }
}
