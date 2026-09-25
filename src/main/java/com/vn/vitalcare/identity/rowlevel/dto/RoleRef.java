package com.vn.vitalcare.identity.rowlevel.dto;

/**
 * A reference to the role a {@code SCOPE} belongs to.
 *
 * <p>Declared here rather than imported from the role slice: reusing that
 * record would make the role API's payload part of this endpoint's contract,
 * so widening one would silently widen the other.
 *
 * <p>The id is nullable because a {@code FILTER} has no role, and because
 * clearing one has to be expressible — Jackson reports an omitted object and an
 * explicit {@code null} identically, so the distinction has to live on the
 * field.
 */
public record RoleRef(Long id) {
}
