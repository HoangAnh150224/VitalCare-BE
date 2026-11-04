package com.vn.vitalcare.identity.audit;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.vn.vitalcare.entity.AuditLog;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import com.vn.vitalcare.share.security.Permissions;
import com.vn.vitalcare.share.web.ListParams;
import com.vn.vitalcare.share.web.ListResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/audit_logs} — the audit log, to search and read (FE-20). There
 * is no write endpoint, and none will be added: BR-24.
 */
@RestController
@RequestMapping("/api/audit_logs")
@Transactional(readOnly = true)
public class AuditLogController {

    private static final Set<String> SORTABLE = Set.of("id", "occurredAt", "action", "entityType");
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("id"));
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final AuditLogRepository logs;
    private final ObjectMapper json;

    public AuditLogController(AuditLogRepository logs, ObjectMapper json) {
        this.logs = logs;
        this.json = json;
    }

    public record Entry(Long id, Long actorId, String actorName, String entityType, Long entityId, String action,
                        JsonNode before, JsonNode after, OffsetDateTime occurredAt, String ipAddress,
                        String userAgent, String requestId, Instant createdAt) {
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.AUDIT_LOGS_READ + "')")
    public ResponseEntity<List<Entry>> list(@RequestParam MultiValueMap<String, String> query) {
        ListParams params = new ListParams(query);
        Page<AuditLog> page = logs.findAll(specification(params), params.pageable(SORTABLE, DEFAULT_SORT));
        return ListResponse.of(page.getContent().stream().map(this::entry).toList(), page.getTotalElements());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.AUDIT_LOGS_READ + "')")
    public Entry get(@PathVariable Long id) {
        return logs.findById(id).map(this::entry).orElseThrow(() -> new ResourceNotFoundException("Audit log", id));
    }

    private Entry entry(AuditLog row) {
        return new Entry(row.getId(), row.getActorId(), row.getActorName(), row.getEntityType(), row.getEntityId(),
                row.getAction(), parse(row.getBeforeState()), parse(row.getAfterState()), row.getOccurredAt(),
                row.getIpAddress(), row.getUserAgent(), row.getRequestId(), row.getCreatedAt());
    }

    private JsonNode parse(String state) {
        try {
            return state == null ? null : json.readTree(state);
        } catch (Exception e) {
            return null;
        }
    }

    private static Specification<AuditLog> specification(ListParams params) {
        List<Specification<AuditLog>> specs = new ArrayList<>();
        for (ListParams.Criterion criterion : params.filters()) {
            String value = criterion.value() == null ? "" : criterion.value().trim();
            try {
                switch (criterion.field()) {
                    case "action" -> specs.add((root, q, cb) -> cb.equal(root.get("action"), value));
                    case "entityType" -> specs.add((root, q, cb) -> cb.equal(root.get("entityType"), value));
                    case "entityId" -> {
                        Long id = Long.valueOf(value);
                        specs.add((root, q, cb) -> cb.equal(root.get("entityId"), id));
                    }
                    case "actorId" -> {
                        Long id = Long.valueOf(value);
                        specs.add((root, q, cb) -> cb.equal(root.get("actorId"), id));
                    }
                    // occurredFrom / occurredTo: whole days in the clinic's zone, both ends included.
                    case "occurredFrom", "occurredTo" -> {
                        LocalDate day = LocalDate.parse(value.substring(0, 10));
                        if (criterion.field().equals("occurredFrom")) {
                            OffsetDateTime start = day.atStartOfDay(ZONE).toOffsetDateTime();
                            specs.add((root, q, cb) -> cb.greaterThanOrEqualTo(root.get("occurredAt"), start));
                        } else {
                            OffsetDateTime end = day.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime();
                            specs.add((root, q, cb) -> cb.lessThan(root.get("occurredAt"), end));
                        }
                    }
                    // occurredAt_gte / occurredAt_lte: whole days in the clinic's zone.
                    case "occurredAt" -> {
                        LocalDate day = LocalDate.parse(value.substring(0, 10));
                        OffsetDateTime start = day.atStartOfDay(ZONE).toOffsetDateTime();
                        OffsetDateTime end = day.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime();
                        specs.add((root, q, cb) -> switch (criterion.operator()) {
                            case GTE -> cb.greaterThanOrEqualTo(root.get("occurredAt"), start);
                            case LTE -> cb.lessThan(root.get("occurredAt"), end);
                            default -> cb.and(cb.greaterThanOrEqualTo(root.get("occurredAt"), start),
                                    cb.lessThan(root.get("occurredAt"), end));
                        });
                    }
                    default -> {
                    }
                }
            } catch (IllegalArgumentException | DateTimeParseException | StringIndexOutOfBoundsException e) {
                specs.add((root, q, cb) -> cb.disjunction());
            }
        }
        params.search().ifPresent(term -> specs.add((root, q, cb) -> cb.or(
                cb.like(cb.lower(root.get("actorName")), ListParams.likePattern(term)),
                cb.like(cb.lower(root.get("entityType")), ListParams.likePattern(term)),
                cb.like(cb.lower(root.get("action")), ListParams.likePattern(term)))));
        return Specification.allOf(specs);
    }
}
