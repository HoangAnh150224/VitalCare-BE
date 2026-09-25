package com.vn.vitalcare.share.data;

import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;

/**
 * The repository interface for an entity under row-level management.
 *
 * <p>It deliberately does <b>not</b> extend {@code CrudRepository} or
 * {@code JpaRepository}. The consequence is the whole reason it exists:
 * {@code findById}, {@code findAll()}, {@code findAllById}, {@code count()},
 * {@code existsById}, {@code getReferenceById} and every derived query simply
 * <em>do not exist to be called by mistake</em>. There is no unscoped read path
 * to forget to scope.
 *
 * <p>{@link JpaSpecificationExecutor} contributes only methods that take a
 * {@code Specification}, so every read has to be handed a scope before it can
 * run. Three write methods are added back, because a repository that cannot
 * save is not a repository — and none of them decides anything about
 * authorisation. Filtering is the {@code Specification}'s job and checking a
 * write is the service's.
 *
 * <p>An unrestricted read is still possible, and that is fine: it means
 * declaring a new method on the concrete sub-interface. A new method on a
 * row-level repository is a thing a reviewer sees, which is the opposite of
 * quietly calling one that was inherited.
 */
@NoRepositoryBean
public interface SecuredRepository<T, ID> extends Repository<T, ID>, JpaSpecificationExecutor<T> {

    /** Saves a new or modified entity. Named unlike {@code save} so its absence elsewhere is obvious. */
    <S extends T> S persist(S entity);

    /** Deletes an entity the caller has already read through a scope. */
    void remove(T entity);

    /**
     * Flushes pending writes.
     *
     * <p>Needed on the create path: identity keys and {@code @PrePersist}
     * stamps only exist afterwards, and checking a policy against an entity
     * that has neither is wrong in every case.
     */
    void flush();
}
