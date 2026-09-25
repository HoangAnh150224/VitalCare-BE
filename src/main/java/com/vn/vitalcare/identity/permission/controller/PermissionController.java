package com.vn.vitalcare.identity.permission.controller;

import com.vn.vitalcare.identity.permission.entity.Permission;
import com.vn.vitalcare.identity.permission.dto.PermissionResponse;
import com.vn.vitalcare.identity.permission.service.PermissionService;
import com.vn.vitalcare.share.security.Permissions;
import com.vn.vitalcare.share.web.ListParams;
import com.vn.vitalcare.share.web.ListResponse;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/permissions} — the permission catalogue, read-only.
 *
 * <p>Needed for the role form's permission picker; there is no write side
 * because a permission only means something once an endpoint checks for it.
 * See {@code PermissionService} for why.
 */
@RestController
@RequestMapping("/api/permissions")
@PreAuthorize("hasAuthority('" + Permissions.PERMISSIONS_READ + "')")
public class PermissionController {

    private final PermissionService service;

    public PermissionController(PermissionService service) {
        this.service = service;
    }

    /** Serves both {@code getList} and {@code getMany}, as elsewhere. */
    @GetMapping
    public ResponseEntity<List<PermissionResponse>> list(@RequestParam MultiValueMap<String, String> query) {
        ListParams params = new ListParams(query);

        List<Long> ids = params.ids();
        if (!ids.isEmpty()) {
            List<PermissionResponse> rows = service.getMany(ids).stream()
                    .map(PermissionResponse::from)
                    .toList();
            return ListResponse.of(rows, rows.size());
        }

        Page<Permission> page = service.list(params);
        return ListResponse.of(page.map(PermissionResponse::from).getContent(), page.getTotalElements());
    }

    @GetMapping("/{id}")
    public PermissionResponse get(@PathVariable Long id) {
        return PermissionResponse.from(service.get(id));
    }
}
