package com.vn.vitalcare.identity.permission.service;

import com.vn.vitalcare.identity.permission.entity.Permission;
import com.vn.vitalcare.identity.permission.repository.PermissionRepository;
import com.vn.vitalcare.identity.permission.repository.PermissionSpecifications;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import com.vn.vitalcare.share.web.ListParams;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only access to the permission catalogue.
 *
 * <p>There is no create or delete here on purpose: a permission that no
 * endpoint checks grants nothing, so inventing one at runtime would produce a
 * row the UI can assign and the API will never honour. New permissions arrive
 * with the code that enforces them, i.e. in a migration.
 */
@Service
@Transactional(readOnly = true)
public class PermissionService {

    /** Sort properties the list endpoint accepts; anything else is ignored. */
    private static final Set<String> SORTABLE = Set.of("id", "code", "name", "systemPermission");

    private static final Sort DEFAULT_SORT = Sort.by(Sort.Order.asc("code"));

    private final PermissionRepository repository;

    public PermissionService(PermissionRepository repository) {
        this.repository = repository;
    }

    public Page<Permission> list(ListParams params) {
        return repository.findAll(
                PermissionSpecifications.from(params),
                params.pageable(SORTABLE, DEFAULT_SORT));
    }

    public Permission get(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Permission", id));
    }

    public List<Permission> getMany(List<Long> ids) {
        return repository.findAllById(ids);
    }

    /**
     * Resolves the ids a role is being granted, refusing the whole set if any
     * one of them is unknown.
     *
     * <p>Silently dropping an unknown id would save a role that grants less
     * than the request asked for while reporting success, which is the worst
     * possible outcome for an authorisation change.
     */
    public List<Permission> resolveAll(List<Long> ids) {
        List<Permission> found = repository.findAllById(ids);
        if (found.size() != Set.copyOf(ids).size()) {
            Set<Long> foundIds = found.stream().map(Permission::getId).collect(Collectors.toSet());
            Long missing = ids.stream().filter(id -> !foundIds.contains(id)).findFirst().orElse(null);
            throw new ResourceNotFoundException("Permission", missing);
        }
        return found;
    }
}
