package com.vn.vitalcare.identity.audit;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.vn.vitalcare.entity.AuditLog;
import com.vn.vitalcare.share.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Writes the audit log (FE-20, BR-24): who did a protected thing, to which
 * record, when, from where, and the state before and after.
 *
 * <p>Not transactional itself: the row goes in through {@link AuditWriter} in
 * a transaction of its own, so a read under a read-only transaction (opening a
 * medical record) can still be recorded, and a failure to write it — caught
 * here, outside that transaction — is logged and never fails the action: the
 * trail serves the action, not the other way round. A change another domain
 * announces is written once it has committed, so a change that rolled back is
 * never on record.
 */
@Service
public class AuditTrail {

    private static final Logger log = LoggerFactory.getLogger(AuditTrail.class);

    private final AuditWriter writer;
    private final ObjectMapper json;
    private final Clock clock;

    public AuditTrail(AuditWriter writer, ObjectMapper json, Clock clock) {
        this.writer = writer;
        this.json = json;
        this.clock = clock;
    }

    /**
     * What another domain announced, once it committed; still on the request's
     * thread, so the caller and the request are at hand.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(com.vn.vitalcare.share.audit.AuditEvent event) {
        record(event.entityType(), event.entityId(), event.action(), event.before(), event.after());
    }

    /**
     * @param entityType what kind of record, as a resource name: {@code users}, {@code medical_records}...
     * @param action     what was done: {@code view}, {@code role_change}, {@code status_change}...
     * @param before     the parts that changed, as they were; null for a read
     * @param after      the parts that changed, as they are now; null for a read
     */
    public void record(String entityType, Long entityId, String action, Map<String, ?> before, Map<String, ?> after) {
        try {
            HttpServletRequest request = currentRequest();
            writer.write(new AuditLog(
                    CurrentUser.id().orElse(null),
                    CurrentUser.displayName().orElse(null),
                    entityType, entityId, action,
                    toJson(before), toJson(after),
                    OffsetDateTime.now(clock),
                    request == null ? null : clientIp(request),
                    request == null ? null : truncate(request.getHeader("User-Agent"), 512),
                    request == null ? null : truncate(request.getHeader("X-Request-Id"), 128)));
        } catch (RuntimeException e) {
            log.warn("Could not write the audit log for {} {} #{}", action, entityType, entityId, e);
        }
    }

    private String toJson(Map<String, ?> state) {
        if (state == null) {
            return null;
        }
        try {
            return json.writeValueAsString(state);
        } catch (JacksonException e) {
            return null;
        }
    }

    private static HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes.getRequest() : null;
    }

    /** The last hop of X-Forwarded-For behind the proxy (Railway), else the connection's own address. */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        String ip = forwarded != null && !forwarded.isBlank()
                ? forwarded.substring(forwarded.lastIndexOf(',') + 1).trim()
                : request.getRemoteAddr();
        // An inet column takes an address only; anything else is not worth failing the record over.
        return ip != null && ip.matches("[0-9a-fA-F:.]{2,45}") ? ip : null;
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
