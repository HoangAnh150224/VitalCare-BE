package com.vn.vitalcare.identity.rowlevel.entity;

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
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * What happened to a policy, and who did it.
 *
 * <p>Append-only: nothing updates or deletes a row here.
 *
 * <p>It deliberately has <b>no foreign key to {@code row_level_policy}</b>,
 * which is the opposite of what the rest of this schema does. The trail of a
 * policy that has been deleted is the most important trail there is, and a
 * foreign key would either take it with the policy or refuse the deletion. The
 * actor's name is copied in for the same reason: the record has to stay
 * readable after the account that made the change is gone.
 */
@Entity
@Table(name = "row_level_policy_audit")
public class RowLevelPolicyAudit {

    public enum Operation {
        CREATE,
        UPDATE,
        DELETE,
        ENABLE,
        DISABLE,
        /** Disabled by the loader because it no longer compiles. Nobody did this; the schema moved. */
        QUARANTINE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "policy_id", nullable = false)
    private Long policyId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Operation operation;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_state", columnDefinition = "jsonb")
    private String beforeState;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_state", columnDefinition = "jsonb")
    private String afterState;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "actor_id")
    private User actor;

    /**
     * Captured rather than joined, so the entry survives the account being
     * deleted. Sized to match {@code users.full_name}, which is where it comes
     * from — a narrower column here would reject the write outright.
     */
    @Column(name = "actor_name", nullable = false, length = 255)
    private String actorName;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected RowLevelPolicyAudit() {
        // for JPA
    }

    public RowLevelPolicyAudit(
            Long policyId, Operation operation, String beforeState, String afterState,
            User actor, String actorName) {
        this.policyId = policyId;
        this.operation = operation;
        this.beforeState = beforeState;
        this.afterState = afterState;
        this.actor = actor;
        this.actorName = actorName;
    }

    @PrePersist
    void onCreate() {
        if (occurredAt == null) {
            occurredAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public Long getPolicyId() {
        return policyId;
    }

    public Operation getOperation() {
        return operation;
    }

    public String getBeforeState() {
        return beforeState;
    }

    public String getAfterState() {
        return afterState;
    }

    public User getActor() {
        return actor;
    }

    public String getActorName() {
        return actorName;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
