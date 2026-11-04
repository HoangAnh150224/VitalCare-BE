package com.vn.vitalcare.share.data;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import java.time.Instant;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Who wrote a row, when, and whether it is still there — what every business
 * entity carries, <b>except its identifier</b>.
 *
 * <p>The name invites adding an {@code @Id} here. Don't: it is left out on
 * purpose, and the next paragraph is why.
 *
 * <p>Deliberately does <b>not</b> own the identifier. {@code Clinic} is keyed
 * by a {@code UUID} in a column called {@code clinic_id} that seven other
 * tables point at, while the rest are keyed by a generated {@code Long id}.
 * A base class holding the key would have to leave one of those two shapes
 * out; holding only the audit columns covers every entity. {@code equals} and
 * {@code hashCode} stay out for the same reason — there is no identifier here
 * to compare.
 *
 * <p>The three timestamps are {@code Instant}, not {@code OffsetDateTime} like
 * the business timestamps on the entities themselves. Not a preference:
 * Spring Data's auditing converts only to {@code LocalDateTime},
 * {@code LocalDate}, {@code LocalTime}, {@code Instant}, {@code Date} or
 * {@code long}, and an {@code OffsetDateTime} field fails at the first insert
 * with "Cannot convert unsupported date type". It also happens to be what
 * every timestamp under {@code identity/} already uses, and the column is
 * {@code timestamp with time zone} either way.
 *
 * <p>The four stamps at the top are written by {@link AuditingEntityListener},
 * which is why they have getters and no setters: a value the framework owns
 * and the application can overwrite is a value you cannot trust afterwards.
 * {@code createdAt} and {@code createdById} are additionally
 * {@code updatable = false}, so an UPDATE cannot rewrite the record of who
 * created the row no matter what the entity in memory says.
 *
 * <p>Both author columns hold a bare {@code users.id} rather than a
 * {@code @ManyToOne User}: a JPA entity from the identity domain must not
 * cross into a business domain, and a foreign key from twenty-two tables to
 * {@code users} would rebuild exactly the coupling that rule exists to
 * prevent. Resolving an id to a name is the service layer's job, on the one
 * screen that needs it.
 *
 * <p>They are nullable because not every write has a signed-in author: a
 * Liquibase seed, a scheduled job and anything running before authentication
 * all leave them empty, and that is the honest answer rather than a fake one.
 *
 * <h2>Soft delete comes with an obligation</h2>
 *
 * <p>{@link #getDeletedAt()} marks a row as gone, but <b>nothing filters it
 * out automatically</b>. Every query over a base entity has to exclude
 * deleted rows itself, and forgetting once means serving data somebody
 * deleted. Compose {@link BaseEntitySpecifications#notDeleted()} into the
 * domain's own {@code Specification} rather than writing the predicate again.
 *
 * <p>This is a chosen trade. Hibernate's {@code @SoftDelete} and
 * {@code @SQLRestriction} both filter on their own and cannot be forgotten,
 * but the first gives up {@code deletedById} and a readable {@code deletedAt},
 * and the second makes a deleted row unreachable through JPA at all — which
 * forecloses ever showing or restoring one.
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @CreatedBy
    @Column(name = "created_by_id", updatable = false)
    private Long createdById;

    @LastModifiedDate
    @Column(name = "updated_at")
    private Instant updatedAt;

    @LastModifiedBy
    @Column(name = "updated_by_id")
    private Long updatedById;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "deleted_by_id")
    private Long deletedById;

    /**
     * Guards against a lost update: two callers editing the row they each read
     * means the second write fails rather than quietly overwriting the first.
     *
     * <p>Consequence worth knowing: saving a detached entity now has to carry
     * the version it was read with, or Hibernate raises
     * {@code OptimisticLockException}.
     */
    @Version
    @Column(name = "version", nullable = false)
    private int version;

    /**
     * Marks the row deleted, recording who and when.
     *
     * <p>Takes the moment as a parameter rather than reading the clock: an
     * entity that calls {@code now()} cannot be tested without waiting, and
     * the clock belongs at the boundary with the other side effects. Same
     * shape as {@code RefreshToken.revoke(Instant)}.
     *
     * <p>Idempotent — deleting twice keeps the first deletion's author and
     * time, because that is the one that actually happened.
     */
    public void markDeleted(Long actorId, Instant when) {
        if (deletedAt == null) {
            deletedAt = when;
            deletedById = actorId;
        }
    }

    /** Clears the deletion, leaving no trace that it happened. */
    public void restore() {
        deletedAt = null;
        deletedById = null;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Long getCreatedById() {
        return createdById;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getUpdatedById() {
        return updatedById;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public Long getDeletedById() {
        return deletedById;
    }

    public int getVersion() {
        return version;
    }
}
