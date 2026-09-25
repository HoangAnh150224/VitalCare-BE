package com.vn.vitalcare.identity.rowlevel.service;

import com.vn.vitalcare.share.security.rowlevel.Action;
import com.vn.vitalcare.share.security.rowlevel.RowLevelViolationException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.data.jpa.domain.Specification;

/**
 * The one thing a domain calls. Everything else in this slice is behind it.
 *
 * <p>A domain reaches it the way it reaches any other domain — through a
 * {@code service}, never a {@code repository}.
 *
 * <p>Every read method hands back a {@link Specification} rather than running
 * anything, so the scope becomes part of the query the caller was going to run
 * anyway. That is what keeps the cost of this whole layer at <b>zero extra SQL
 * statements</b>.
 */
public interface RowLevelSecurity {

    /**
     * The scope for one entity and action.
     *
     * <p>Always {@code .and(...)}ed <em>before</em> the caller's own filters.
     * {@code Specification.and} has no subtraction, so nothing a query string
     * can say will widen what comes back.
     *
     * <p>Unrestricted when there is no authenticated caller, when the entity is
     * not under management, or inside {@link #asSystem}.
     */
    <T> Specification<T> scope(Class<T> entityClass, Action action);

    /** {@link #scope} and {@code id = ?} — for get, update and delete. */
    <T> Specification<T> byId(Class<T> entityClass, Action action, Object id);

    /**
     * {@link #scope} and {@code id IN (?)} — for {@code getMany}.
     *
     * <p>Returns fewer rows than ids were asked for when some are out of scope.
     * That is the correct answer, not a defect: a client uses {@code getMany}
     * to resolve foreign-key labels, so an absent row shows as a dash rather
     * than as somebody else's data.
     */
    <T> Specification<T> byIds(Class<T> entityClass, Action action, Collection<?> ids);

    /**
     * The {@code WITH CHECK} half: is the state this write would leave behind
     * still inside the caller's write scope?
     *
     * <p>This is what stops a row being pushed <em>out of</em> its own scope.
     * Without it, {@code USING} alone is a privilege escalation by laundering.
     *
     * <p>Evaluated in memory on an entity already loaded, so it costs nothing.
     *
     * @throws RowLevelViolationException answered {@code 422}, naming the field
     */
    void checkWritable(Object entity);

    /**
     * The {@code _can} flags for one row: {@code USING} and {@code WITH CHECK}
     * together, against the state the row is in now.
     *
     * <p>Empty when the entity is unmanaged, or when an association a policy
     * needs was not fetched: better no flag than an N+1 nobody notices.
     */
    Map<String, Boolean> capabilities(Object entity);

    /** Whether the caller may read this row — for masking an embedded summary. */
    boolean canRead(Object entity);

    /** The associations a managed entity must have loaded before flags can be computed. */
    List<String> fetchHints(Class<?> entityClass);

    /** Resources whose rows are actually narrowed for the current caller, for the empty-state message. */
    List<String> scopedResourcesForCurrentUser();

    /**
     * The same, for a named account.
     *
     * <p>Needed because the live channel pushes an account its grants when there
     * is no request in flight and therefore no security context to read.
     */
    List<String> scopedResourcesFor(long userId);

    /**
     * Runs something with row-level filtering off, on purpose and on the record.
     *
     * <p>Scheduled work, migrations and the sign-in path have no caller to be
     * scoped to. Those get the full scope automatically, because there is no
     * principal. This is for the other case: a deliberate bypass while somebody
     * <em>is</em> signed in.
     */
    <R> R asSystem(String taskName, Supplier<R> work);

    void asSystem(String taskName, Runnable work);
}
