package com.vn.vitalcare.share.data;

import org.springframework.data.jpa.domain.Specification;

/**
 * The predicate every read of an {@link BaseEntity} owes.
 *
 * <p>Soft delete here is not enforced by the mapping, so this is the one line
 * that keeps a deleted row out of a result. It lives in one place so that the
 * twenty-two domains do not each write {@code cb.isNull(root.get("deletedAt"))}
 * from memory — and so that changing how deletion is represented is one edit
 * rather than a search.
 */
public final class BaseEntitySpecifications {

    private BaseEntitySpecifications() {
    }

    /**
     * Rows that have not been deleted.
     *
     * <p>Compose it into the domain's own specification:
     * {@snippet :
     * return Specification.allOf(specs).and(BaseEntitySpecifications.notDeleted());
     * }
     *
     * <p>The attribute name is checked at query build time, not compile time,
     * so this only works for an entity extending {@link BaseEntity} —
     * anything else fails with {@code IllegalArgumentException} on first use.
     */
    public static <T> Specification<T> notDeleted() {
        return (root, query, cb) -> cb.isNull(root.get("deletedAt"));
    }
}
