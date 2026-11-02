package com.vn.vitalcare.share.audit;

import java.util.Map;

/**
 * A protected action for the audit log (FE-20), announced by whoever did it
 * and written by {@code AuditTrail}. An event rather than a call so any domain
 * can say so without depending on the one that writes it.
 *
 * @param entityType what kind of record, as a resource name: {@code users}, {@code invoices}...
 * @param action     what was done: {@code role_change}, {@code status_change}, {@code pay}...
 * @param before     the parts that changed, as they were; null for a read
 * @param after      the parts that changed, as they are now; null for a read
 */
public record AuditEvent(String entityType, Long entityId, String action, Map<String, ?> before, Map<String, ?> after) {

    public static AuditEvent of(String entityType, Long entityId, String action) {
        return new AuditEvent(entityType, entityId, action, null, null);
    }
}
