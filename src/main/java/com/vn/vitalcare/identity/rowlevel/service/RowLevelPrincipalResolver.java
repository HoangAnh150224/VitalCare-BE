package com.vn.vitalcare.identity.rowlevel.service;

import com.vn.vitalcare.identity.auth.service.AuthorityCache;
import com.vn.vitalcare.identity.auth.service.AuthorizationService;
import com.vn.vitalcare.share.security.CurrentUser;
import com.vn.vitalcare.share.security.rowlevel.RowLevelPrincipal;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Builds the {@link RowLevelPrincipal} for the request in flight.
 *
 * <p>Everything it needs — placement, role ids — is already on the authority
 * cache entry that authorised the request a moment ago, so this costs no query.
 * That is not an optimisation but the premise: a scope is evaluated on every
 * list, and a lookup here would be a lookup per request per resource.
 *
 * <p>Nothing is read from the access token, and nothing was captured at sign-in.
 * A token minted this morning says who you are; where you sit is answered now,
 * from a cache the domains that change it invalidate.
 *
 * <h2>Why the authorization service is looked up lazily</h2>
 *
 * <p>This is the one place in the application where the dependency graph is
 * genuinely circular, and it is worth understanding rather than working around
 * again in the next slice:
 *
 * <pre>
 *   AuthorityCache -&gt; UserService -&gt; ... -&gt; RowLevelSecurity
 *                  -&gt; RowLevelPrincipalResolver -&gt; AuthorizationService -&gt; AuthorityCache
 * </pre>
 *
 * <p>Resolving <em>who you are</em> means reading rows, and reading rows means
 * knowing who you are. It appears the moment a resource <em>inside</em> that
 * chain — such as {@code users} or {@code roles} — comes under row-level
 * management.
 *
 * <p><b>The cycle is only about construction order, not about logic.</b> The
 * authentication path itself is never scoped: it runs before any principal
 * exists, and {@code RowLevelSecurity.scope} answers the full scope when there
 * is none. So the edge that has to be deferred is this one, and deferring it
 * here fixes it once for every resource that will ever be added rather than
 * being rediscovered per domain.
 *
 * <p>An {@link ObjectProvider} rather than {@code @Lazy}: the deferral is then a
 * visible property of the field, with somewhere to write down why, instead of an
 * annotation that reads like a performance hint.
 */
@Component
public class RowLevelPrincipalResolver {

    private final ObjectProvider<AuthorizationService> authorizationService;

    public RowLevelPrincipalResolver(ObjectProvider<AuthorizationService> authorizationService) {
        this.authorizationService = authorizationService;
    }

    /**
     * The caller, or empty when there is nobody.
     *
     * <p>Empty is not an error and must not become one. Startup, scheduled work,
     * and the sign-in flow itself all run with no authentication, and they still
     * read rows. {@code RowLevelSecurity} answers empty with the full scope,
     * which is also what stops this resolver recursing if {@code users} is ever
     * brought under management.
     */
    public Optional<RowLevelPrincipal> current() {
        return CurrentUser.id().flatMap(this::forUser);
    }

    /** The principal for a named account — for {@code /explain} and {@code /simulate}. */
    public Optional<RowLevelPrincipal> forUser(long userId) {
        return authorizationService.getObject().entryOf(userId)
                .map(entry -> toPrincipal(userId, entry));
    }

    private RowLevelPrincipal toPrincipal(long userId, AuthorityCache.Entry entry) {
        return new RowLevelPrincipal(
                userId,
                entry.organizationId(),
                entry.departmentId(),
                Set.copyOf(entry.roles()),
                entry.roleIds());
    }
}
