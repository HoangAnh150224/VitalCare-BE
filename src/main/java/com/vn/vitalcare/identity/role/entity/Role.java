package com.vn.vitalcare.identity.role.entity;

import com.vn.vitalcare.identity.permission.entity.Permission;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * A named bundle of {@link Permission}s.
 *
 * <p>Roles carry no authority of their own: nothing in the API checks for a
 * role name, only for the permission codes the role happens to grant. Adding a
 * capability to a job function is therefore a data change here rather than a
 * code change at every guarded endpoint.
 */
@Entity
@Table(name = "roles")
public class Role {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Stable identifier, e.g. {@code ADMIN}. Referenced by the seed data. */
    @Column(nullable = false, length = 64, unique = true)
    private String code;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(length = 500)
    private String description;

    /**
     * Marks a role the application itself depends on.
     *
     * <p>The service refuses to delete a system role or change its code, so an
     * admin tidying up the role list cannot leave the system with no way back
     * in. The permissions it grants stay editable.
     */
    @Column(name = "system_role", nullable = false)
    private boolean systemRole;

    // Eager: a role is essentially never useful without knowing what it grants
    // — the token minted at login needs every permission of every role the user
    // holds, and the role list view renders the count on each row.
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "role_permissions",
            joinColumns = @JoinColumn(name = "role_id"),
            inverseJoinColumns = @JoinColumn(name = "permission_id"))
    private Set<Permission> permissions = new LinkedHashSet<>();

    protected Role() {
        // for JPA
    }

    public Role(String code, String name, String description, boolean systemRole) {
        this.code = code;
        this.name = name;
        this.description = description;
        this.systemRole = systemRole;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isSystemRole() {
        return systemRole;
    }

    public Set<Permission> getPermissions() {
        return permissions;
    }

    public void setPermissions(Set<Permission> permissions) {
        this.permissions.clear();
        this.permissions.addAll(permissions);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Role role)) {
            return false;
        }
        return id != null && id.equals(role.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
