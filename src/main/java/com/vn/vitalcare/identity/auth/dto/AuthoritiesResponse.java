package com.vn.vitalcare.identity.auth.dto;

import com.vn.vitalcare.identity.auth.service.AuthorityCache;
import java.util.List;

/**
 * What the caller may do — as {@code GET /auth/permissions} answers it, and as
 * the live channel pushes it.
 *
 * <p>One shape on both paths, on purpose. The client stores whatever arrives
 * without caring how it got there, so a permission change delivered over the
 * socket and one fetched after a reload leave it in exactly the same state.
 *
 * @param roles       the role codes the account holds, for display. Nothing
 *                    authorises on them
 * @param permissions every permission code from every one of those roles,
 *                    flattened, de-duplicated and sorted
 * @param version     the same stamp the {@code X-Auth-Version} header carries
 * @param scopedResources the resources whose rows are actually narrowed for
 *                    this account, computed from the row-level policy cache
 */
public record AuthoritiesResponse(
        List<String> roles,
        List<String> permissions,
        String version,
        List<String> scopedResources) {

    public static AuthoritiesResponse from(AuthorityCache.Entry entry, List<String> scopedResources) {
        return new AuthoritiesResponse(
                entry.roles(), entry.permissions(), entry.version(), scopedResources);
    }
}
