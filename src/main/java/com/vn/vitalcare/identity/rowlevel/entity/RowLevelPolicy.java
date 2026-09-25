package com.vn.vitalcare.identity.rowlevel.entity;

import com.vn.vitalcare.identity.role.entity.Role;
import com.vn.vitalcare.identity.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One row-level policy, editable at runtime.
 *
 * <p>The other half of the design lives in source code, as a
 * {@code RowLevelPolicySet} bean, and can express anything Java can. This half
 * is data: a closed vocabulary, an admin screen, an audit trail, and a switch
 * to turn it off. The split is what lets policies be both expressive and
 * editable-by-a-person without an expression language anywhere near the
 * runtime.
 *
 * <p>{@link #getScope()} holds a {@code ScopeTree} as JSON. It is kept as a
 * string rather than mapped to the record hierarchy, because the entity is
 * storage and the tree is a compiled artefact: parsing and type-checking it
 * needs the resource's field declarations, which are not the persistence
 * layer's business. A tree that no longer compiles is quarantined at load
 * rather than making the row unreadable.
 */
@Entity
@Table(name = "row_level_policy")
public class RowLevelPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private PolicyKind kind;

    /**
     * The role this widens — null, and only null, for a {@link PolicyKind#FILTER}.
     *
     * <p>{@code ON DELETE CASCADE}, which is the deliberate exception to the
     * {@code RESTRICT} the rest of this schema uses. An orphaned scope is not
     * data worth keeping; it is a fragment of a rule about nobody.
     */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "role_id")
    private Role role;

    /** The resource name, verbatim: {@code tasks}, {@code patients}. */
    @Column(nullable = false, length = 64)
    private String resource;

    /** {@code read}, {@code write} or {@code delete}. Checked against the registry in code, not by a constraint. */
    @Column(nullable = false, length = 16)
    private String action;

    @Column(nullable = false, length = 160)
    private String name;

    /** Free-form grouping, for the admin screen only. */
    @Column(name = "policy_group", length = 64)
    private String policyGroup;

    @Column(length = 500)
    private String description;

    /** The condition tree as JSON. {@code {"all":[]}} is the empty tree — the full scope. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String scope;

    /**
     * The {@code WITH CHECK} clause: what the row is allowed to look like
     * <em>after</em> a write. Null means "the same as {@link #scope}", which is
     * both Postgres's default and the safe one — a condition applied to the
     * resulting state is what stops a row being pushed out of its writer's own
     * sight.
     *
     * <p>It has to be separable, though, because the two clauses answer
     * different questions once the condition is about workflow state rather
     * than ownership. "You may edit drafts" is a statement about which rows may
     * be touched; applied to the state after the save it also forbids
     * publishing one, which makes the only meaningful edit impossible.
     *
     * <p>Only meaningful for {@code write}: {@code read} and {@code delete}
     * leave no new state to check.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "check_scope", columnDefinition = "jsonb")
    private String checkScope;

    @Column(nullable = false)
    private boolean enabled = true;

    /**
     * Why this policy was disabled at load, when it was.
     *
     * <p>Filled by the quarantine path: a schema that moved under an existing
     * policy, a hand-run migration, a restore from an old backup. Refusing to
     * start over one row of data would turn a configuration error into an
     * outage, at an arbitrary later restart, with nobody able to connect the
     * two events.
     */
    @Column(name = "invalid_reason", length = 500)
    private String invalidReason;

    /**
     * Who wrote it. {@code ON DELETE SET NULL}: worth keeping, not worth
     * refusing to delete a leaver's account over.
     */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "created_by_id")
    private User createdBy;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "updated_by_id")
    private User updatedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected RowLevelPolicy() {
        // for JPA
    }

    public RowLevelPolicy(PolicyKind kind, Role role, String resource, String action, String name, String scope) {
        this.kind = kind;
        this.role = role;
        this.resource = resource;
        this.action = action;
        this.name = name;
        this.scope = scope;
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

    public Long getId() {
        return id;
    }

    public PolicyKind getKind() {
        return kind;
    }

    public void setKind(PolicyKind kind) {
        this.kind = kind;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public String getResource() {
        return resource;
    }

    public void setResource(String resource) {
        this.resource = resource;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPolicyGroup() {
        return policyGroup;
    }

    public void setPolicyGroup(String policyGroup) {
        this.policyGroup = policyGroup;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    /** Null when the write check reuses {@link #getScope()}. */
    public String getCheckScope() {
        return checkScope;
    }

    public void setCheckScope(String checkScope) {
        this.checkScope = checkScope;
    }

    /** The tree that actually governs the state after a write. */
    public String effectiveCheckScope() {
        return checkScope == null ? scope : checkScope;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getInvalidReason() {
        return invalidReason;
    }

    public void setInvalidReason(String invalidReason) {
        this.invalidReason = invalidReason;
    }

    public User getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(User createdBy) {
        this.createdBy = createdBy;
    }

    public User getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(User updatedBy) {
        this.updatedBy = updatedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RowLevelPolicy policy)) {
            return false;
        }
        return id != null && id.equals(policy.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
