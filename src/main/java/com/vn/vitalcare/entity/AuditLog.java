package com.vn.vitalcare.entity;

import com.vn.vitalcare.share.data.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One protected action, as it happened (FE-20, BR-24): who, what, on which
 * record, when, from where, and the state before and after. Append-only — no
 * setters, and nothing in the application updates or deletes a row.
 *
 * <p>{@code occurredAt} and {@code actorId} below are this table's own record
 * of who did what and when — they describe the <em>event</em>, which is the
 * whole point of the row. {@link BaseEntity} adds {@code createdAt} and
 * {@code createdById} describing the <em>write</em>: the same fact twice, kept
 * so every business table carries the same columns. The actor is a bare
 * {@code users.id} with the name as it was, for the reason {@code BaseEntity}
 * gives about entities of the identity domain.
 */
@Entity
@Table(name = "audit_log")
public class AuditLog extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "actor_id", updatable = false)
    private Long actorId;

    @Column(name = "actor_name", updatable = false)
    private String actorName;

    /** What kind of record: {@code user}, {@code medical_record}, {@code device_assignment}, {@code invoice}... */
    @Column(name = "entity_type", length = 128, updatable = false)
    private String entityType;

    @Column(name = "entity_id", updatable = false)
    private Long entityId;

    /** What was done: {@code view}, {@code role_change}, {@code status_change}, {@code assign}, {@code pay}... */
    @Column(name = "action", length = 64, updatable = false)
    private String action;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_state", columnDefinition = "jsonb", updatable = false)
    private String beforeState;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_state", columnDefinition = "jsonb", updatable = false)
    private String afterState;

    @Column(name = "occurred_at", updatable = false)
    private OffsetDateTime occurredAt;

    /** An {@code inet} in the database: cast on the way in, read back as text. */
    @ColumnTransformer(read = "host(ip_address)", write = "cast(? as inet)")
    @Column(name = "ip_address", columnDefinition = "inet", updatable = false)
    private String ipAddress;

    @Column(name = "user_agent", length = 512, updatable = false)
    private String userAgent;

    @Column(name = "request_id", length = 128, updatable = false)
    private String requestId;

    protected AuditLog() {
    }

    public AuditLog(Long actorId, String actorName, String entityType, Long entityId, String action,
                    String beforeState, String afterState, OffsetDateTime occurredAt, String ipAddress,
                    String userAgent, String requestId) {
        this.actorId = actorId;
        this.actorName = actorName;
        this.entityType = entityType;
        this.entityId = entityId;
        this.action = action;
        this.beforeState = beforeState;
        this.afterState = afterState;
        this.occurredAt = occurredAt;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.requestId = requestId;
    }

    public Long getId() {
        return id;
    }

    public Long getActorId() {
        return actorId;
    }

    public String getActorName() {
        return actorName;
    }

    public String getEntityType() {
        return entityType;
    }

    public Long getEntityId() {
        return entityId;
    }

    public String getAction() {
        return action;
    }

    public String getBeforeState() {
        return beforeState;
    }

    public String getAfterState() {
        return afterState;
    }

    public OffsetDateTime getOccurredAt() {
        return occurredAt;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public String getRequestId() {
        return requestId;
    }
}
