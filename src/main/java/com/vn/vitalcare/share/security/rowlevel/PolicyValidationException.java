package com.vn.vitalcare.share.security.rowlevel;

/**
 * A policy that cannot be compiled: an unknown field, an operator the field's
 * type does not support, a literal that will not coerce, a context key nobody
 * registered.
 *
 * <p>Raised in two places that treat it very differently, and the difference is
 * the point.
 *
 * <p>When a policy is <b>saved</b>, this is a hard {@code 422} naming the node
 * at fault. That is the only place a broken policy is refused outright, because
 * it is the only place somebody is sitting in front of a screen able to fix it.
 *
 * <p>When the cache <b>loads</b> at startup — a schema that moved under an
 * existing policy, a hand-run migration, a restore from an old backup — it is
 * caught and the policy is quarantined instead. Refusing to start a node over
 * one row of data would turn a configuration mistake into an outage, and one
 * that happens at some arbitrary later restart, hours after the change that
 * caused it, with nobody able to connect the two.
 *
 * @param node a pointer to the offending part of the tree, such as
 *             {@code any[1].field}, so the admin screen can highlight it
 */
public class PolicyValidationException extends RuntimeException {

    private final String node;

    public PolicyValidationException(String node, String message) {
        super(node == null || node.isBlank() ? message : "%s: %s".formatted(node, message));
        this.node = node;
    }

    public String node() {
        return node;
    }
}
