package com.vn.vitalcare.share.security;

/**
 * The permission codes seeded by {@code 005-seed-security.sql}, as constants.
 *
 * <p>These are compile-time constants so that {@code @PreAuthorize} can be
 * written as {@code hasAuthority(Permissions.USERS_WRITE)} — a typo becomes a
 * compile error instead of an endpoint that silently authorises nobody.
 *
 * <p>The code is {@code resource:action}, where the resource half is verbatim
 * the resource name the frontend declares in {@code routes.tsx}. That is not
 * cosmetic: it is what lets the admin UI's access control provider build the
 * code it needs from the resource and action it is already handed, rather than
 * carrying a second copy of this mapping that could drift.
 *
 * <p>Three actions cover the six Refine actions: {@code list}/{@code show} need
 * {@code read}, {@code create}/{@code edit}/{@code clone} need {@code write},
 * and {@code delete} needs {@code delete}.
 */
public final class Permissions {

    public static final String USERS_READ = "users:read";
    public static final String USERS_WRITE = "users:write";
    public static final String USERS_DELETE = "users:delete";

    public static final String ROLES_READ = "roles:read";
    public static final String ROLES_WRITE = "roles:write";
    public static final String ROLES_DELETE = "roles:delete";

    public static final String PERMISSIONS_READ = "permissions:read";

    // ADMIN only, and 005-seed-row-level-permissions spells that out rather
    // than joining: editing a data scope is editing the security model, so it
    // is not a grant to hand around. There is deliberately no row-level scope
    // on the policy table itself -- deciding which policies you may see would
    // need the policies you may see.
    public static final String ROW_LEVEL_POLICIES_READ = "row_level_policies:read";
    public static final String ROW_LEVEL_POLICIES_WRITE = "row_level_policies:write";
    public static final String ROW_LEVEL_POLICIES_DELETE = "row_level_policies:delete";

    // A new business domain (patient, appointment, billing, ...) adds its own
    // three constants here, seeded in its own migration, the same way this
    // set was added on top of USERS_*/ROLES_*/PERMISSIONS_READ.

    private Permissions() {
    }
}
