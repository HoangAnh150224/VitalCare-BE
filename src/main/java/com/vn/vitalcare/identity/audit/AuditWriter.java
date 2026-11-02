package com.vn.vitalcare.identity.audit;

import com.vn.vitalcare.entity.AuditLog;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inserts one audit row in a transaction of its own. A bean apart from
 * {@link AuditTrail} so the call goes through the proxy, and so the trail can
 * catch a failure here without it marking the caller's transaction for rollback.
 */
@Component
public class AuditWriter {

    private final AuditLogRepository logs;

    public AuditWriter(AuditLogRepository logs) {
        this.logs = logs;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(AuditLog row) {
        logs.save(row);
    }
}
