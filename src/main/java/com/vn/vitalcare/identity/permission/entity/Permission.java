package com.vn.vitalcare.identity.permission.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;

/**
 * A single capability the API can be asked to authorise.
 *
 * <p>{@link #getCode()} is the whole point of the entity: it is the string
 * {@code @PreAuthorize} matches, the authority the access token carries, and
 * the value the client's access control provider asks about. Everything else
 * here is a label for a human.
 *
 * <p>The catalogue is seeded by Liquibase and read-only over the API — a
 * permission that no endpoint checks would grant nothing, so inventing one at
 * runtime is not a meaningful operation.
 */
@Entity
@Table(name = "permissions")
public class Permission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** {@code resource:action}, e.g. {@code user:write}. */
    @Column(nullable = false, length = 128, unique = true)
    private String code;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(length = 500)
    private String description;

    /**
     * Marks a permission the application itself relies on.
     *
     * <p>The mirror of {@code system_role}: a code that arrived in a migration
     * alongside the {@code @PreAuthorize} that checks it, rather than one
     * somebody added afterwards.
     */
    @Column(name = "system_permission", nullable = false)
    private boolean systemPermission;

    protected Permission() {
        // for JPA
    }

    public Permission(String code, String name, String description, boolean systemPermission) {
        this.code = code;
        this.name = name;
        this.description = description;
        this.systemPermission = systemPermission;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public boolean isSystemPermission() {
        return systemPermission;
    }

    /**
     * Identity by primary key.
     *
     * <p>Permissions live in a {@code Set} on {@link
     * com.vn.vitalcare.identity.role.entity.Role}, so the collection needs
     * these to behave. A transient instance is only ever equal to itself,
     * which is the usual JPA compromise.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Permission permission)) {
            return false;
        }
        return id != null && id.equals(permission.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
