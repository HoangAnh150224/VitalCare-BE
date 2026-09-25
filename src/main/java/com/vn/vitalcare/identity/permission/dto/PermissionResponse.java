package com.vn.vitalcare.identity.permission.dto;

import com.vn.vitalcare.identity.permission.entity.Permission;

/** Wire shape of a permission. */
public record PermissionResponse(
        Long id, String code, String name, String description, boolean systemPermission) {

    public static PermissionResponse from(Permission permission) {
        return new PermissionResponse(
                permission.getId(),
                permission.getCode(),
                permission.getName(),
                permission.getDescription(),
                permission.isSystemPermission());
    }
}
