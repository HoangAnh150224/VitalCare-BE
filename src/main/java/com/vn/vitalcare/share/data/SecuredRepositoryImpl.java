package com.vn.vitalcare.share.data;

import jakarta.persistence.EntityManager;
import org.springframework.data.jpa.repository.support.JpaEntityInformation;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The base class behind every repository in this application.
 *
 * <p>Registered through {@code @EnableJpaRepositories(repositoryBaseClass =
 * ...)} on the application class. Registering it globally rather than for the
 * secured repositories alone is harmless and simpler: Spring Data only exposes
 * what a repository's own interface declares, so the three methods below are
 * reachable exactly where {@link SecuredRepository} asks for them.
 *
 * <p><b>There is not one line of authorisation logic here, on purpose.</b> This
 * class exists only to lend {@code save}, {@code delete} and {@code flush}
 * back to an interface that deliberately inherits nothing. Filtering lives in
 * the {@code Specification} a read is handed; checking a write lives in the
 * service.
 */
public class SecuredRepositoryImpl<T, ID> extends SimpleJpaRepository<T, ID> {

    public SecuredRepositoryImpl(JpaEntityInformation<T, ?> entityInformation, EntityManager entityManager) {
        super(entityInformation, entityManager);
    }

    @Transactional
    public <S extends T> S persist(S entity) {
        return save(entity);
    }

    @Transactional
    public void remove(T entity) {
        delete(entity);
    }
}
