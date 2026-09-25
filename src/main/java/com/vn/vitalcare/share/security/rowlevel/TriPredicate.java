package com.vn.vitalcare.share.security.rowlevel;

/**
 * The in-memory half of a design-time policy: the same condition its
 * {@code Specification} expresses, answered against an entity already loaded.
 *
 * <p>Written by hand and paired with the {@code Specification}, because nothing
 * can generate one from the other — a design-time policy is arbitrary Java, and
 * that expressive power is precisely what makes it uncompilable. The price is
 * that the pair has to be kept in step, which is why every design-time policy
 * owes the cross-check test that the runtime compilers get for free.
 */
@FunctionalInterface
public interface TriPredicate<T> {

    Tri test(T entity, RowLevelPrincipal principal);
}
