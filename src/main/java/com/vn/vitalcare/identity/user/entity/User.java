package com.vn.vitalcare.identity.user.entity;

import com.vn.vitalcare.identity.permission.entity.Permission;
import com.vn.vitalcare.identity.role.entity.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * An account that can sign in.
 *
 * <p>The password is only ever held as {@link #getPasswordHash()} and never
 * leaves this class in any other form: no DTO exposes it, and the service layer
 * only ever writes an already-encoded value into it.
 *
 * <p>Unlike the dth-framework original this entity carries no
 * {@code organization}/{@code department} placement — that domain was not
 * ported. {@link com.vn.vitalcare.identity.rowlevel.service.RowLevelPrincipal}
 * still declares those two fields for forward compatibility; a resolver simply
 * supplies {@code null} for both until such a domain exists.
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The sign-in identifier, in E.164 ({@code +84901234567}).
     *
     * <p>Always normalised before it reaches here: {@code 0901234567} and
     * {@code +84901234567} are one number, and the unique constraint can only
     * say so if a single spelling is stored. {@code UserService} owns that
     * conversion, on the way in and on the way to a lookup.
     */
    @Column(nullable = false, length = 20, unique = true)
    private String phone;

    /**
     * Secondary, and only for sending notifications — nothing authenticates
     * against it, so it is never verified and must not be treated as proof of
     * anything. Still unique, so two accounts cannot claim one inbox and cross
     * their notifications.
     */
    @Column(length = 255, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "full_name", nullable = false, length = 255)
    private String fullName;

    @Column(name = "avatar_url", length = 500)
    private String avatarUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private UserStatus status = UserStatus.ACTIVE;

    // Eager: the list view renders the role badges on every row, and signing in
    // needs the whole role graph to mint a token, so a lazy proxy here would
    // only turn one query into N.
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "user_roles",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<Role> roles = new LinkedHashSet<>();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    protected User() {
        // for JPA
    }

    public User(String phone, String email, String passwordHash, String fullName, UserStatus status) {
        this.phone = phone;
        this.email = email;
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.status = status;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /**
     * Every permission code this user holds, from every role, de-duplicated.
     *
     * <p>This is what is stamped into the access token at login, which is why
     * authorising a request never touches the database.
     */
    public Set<String> permissionCodes() {
        Set<String> codes = new TreeSet<>();
        for (Role role : roles) {
            for (Permission permission : role.getPermissions()) {
                codes.add(permission.getCode());
            }
        }
        return codes;
    }

    /** Role codes, in the same shape and for the same reason as above. */
    public Set<String> roleCodes() {
        Set<String> codes = new TreeSet<>();
        for (Role role : roles) {
            codes.add(role.getCode());
        }
        return codes;
    }

    /** Only an active account may authenticate. */
    public boolean canSignIn() {
        return status == UserStatus.ACTIVE;
    }

    public Long getId() {
        return id;
    }

    public String getPhone() {
        return phone;
    }

    /** Takes an already-normalised E.164 number; normalising is the service's job. */
    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    /** Takes an already-encoded hash; encoding is the service layer's job. */
    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }

    public UserStatus getStatus() {
        return status;
    }

    public void setStatus(UserStatus status) {
        this.status = status;
    }

    public Set<Role> getRoles() {
        return roles;
    }

    public void setRoles(Set<Role> roles) {
        this.roles.clear();
        this.roles.addAll(roles);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(Instant lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof User user)) {
            return false;
        }
        return id != null && id.equals(user.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
