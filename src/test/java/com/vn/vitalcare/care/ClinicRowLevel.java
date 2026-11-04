package com.vn.vitalcare.care;

import com.vn.vitalcare.identity.rowlevel.service.RowLevelSecurity;
import com.vn.vitalcare.share.security.rowlevel.Action;
import com.vn.vitalcare.share.security.rowlevel.RowLevelViolationException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.PropertyAccessorFactory;
import org.springframework.data.jpa.domain.Specification;

/**
 * {@link RowLevelSecurity} as the seeded clinic policies make it behave, for
 * services tested over mocked repositories: a caller with a clinic reaches that
 * clinic's rows ({@code clinicId eq user.clinicId}); a caller with none is the
 * administrator and reaches everything ({@code all: []}).
 *
 * <p>A mocked repository cannot run a specification, so a test answers
 * {@code findOne}/{@code exists} with {@link #canRead} — the in-memory twin of
 * the same rule. That the SQL and in-memory halves agree is
 * {@code CompilerAgreementTest}'s job, not this one's.
 */
public final class ClinicRowLevel implements RowLevelSecurity {

    private final Supplier<UUID> clinic;

    /** @param clinic the signed-in caller's clinic, read on every check; null for an administrator */
    public ClinicRowLevel(Supplier<UUID> clinic) {
        this.clinic = clinic;
    }

    /** An administrator: nothing is narrowed. */
    public static ClinicRowLevel unrestricted() {
        return new ClinicRowLevel(() -> null);
    }

    @Override
    public <T> Specification<T> scope(Class<T> entityClass, Action action) {
        return (root, query, cb) -> cb.conjunction();
    }

    @Override
    public <T> Specification<T> byId(Class<T> entityClass, Action action, Object id) {
        return (root, query, cb) -> cb.equal(root.get("id"), id);
    }

    @Override
    public <T> Specification<T> byIds(Class<T> entityClass, Action action, Collection<?> ids) {
        return (root, query, cb) -> root.get("id").in(ids);
    }

    @Override
    public void checkWritable(Object entity) {
        if (!canRead(entity)) {
            throw new RowLevelViolationException("clinicId", String.valueOf(clinicIdOf(entity)));
        }
    }

    @Override
    public Map<String, Boolean> capabilities(Object entity) {
        return Map.of();
    }

    @Override
    public boolean canRead(Object entity) {
        UUID own = clinic.get();
        return own == null || Objects.equals(own, clinicIdOf(entity));
    }

    @Override
    public boolean hasUnrestrictedScope(Class<?> entityClass, Action action) {
        return clinic.get() == null;
    }

    @Override
    public List<String> fetchHints(Class<?> entityClass) {
        return List.of();
    }

    @Override
    public List<String> scopedResourcesForCurrentUser() {
        return clinic.get() == null ? List.of() : List.of("employees", "appointments", "devices");
    }

    @Override
    public List<String> scopedResourcesFor(long userId) {
        return scopedResourcesForCurrentUser();
    }

    @Override
    public <R> R asSystem(String taskName, Supplier<R> work) {
        return work.get();
    }

    @Override
    public void asSystem(String taskName, Runnable work) {
        work.run();
    }

    private static Object clinicIdOf(Object entity) {
        return PropertyAccessorFactory.forBeanPropertyAccess(entity).getPropertyValue("clinicId");
    }
}
