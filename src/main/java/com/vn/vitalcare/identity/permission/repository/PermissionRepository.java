package com.vn.vitalcare.identity.permission.repository;

import com.vn.vitalcare.identity.permission.entity.Permission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Not row-level managed, and not by oversight — {@code permissions} is a
 * read-only catalogue written by migrations.
 */
public interface PermissionRepository
        extends JpaRepository<Permission, Long>, JpaSpecificationExecutor<Permission> {
}
