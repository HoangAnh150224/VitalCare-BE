package com.vn.vitalcare.identity.audit;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.vn.vitalcare.entity.AuditLog;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import com.vn.vitalcare.share.security.Permissions;
import com.vn.vitalcare.share.web.ListParams;
import com.vn.vitalcare.share.web.ListResponse;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    /**
     * What the audit screen shows for an entity type and an action — the FE's
     * {@code humanizeResource} and {@code AUDIT_ACTION_LABELS} — so a search for
     * the words on screen ("Người dùng", "Xoá") finds the rows they label, and
     * not only a search for the stored code. Keep in step with those two.
     */
    private static final Map<String, String> ENTITY_LABELS = Map.ofEntries(
            Map.entry("users", "Người dùng"),
            Map.entry("employees", "Nhân viên"),
            Map.entry("medical_records", "Hồ sơ bệnh án"),
            Map.entry("vital_signs", "Sinh hiệu"),
            Map.entry("alerts", "Cảnh báo"),
            Map.entry("alert_rules", "Quy tắc cảnh báo"),
            Map.entry("emergency_alerts", "Cảnh báo khẩn"),
            Map.entry("monitoring_orders", "Chỉ định theo dõi"),
            Map.entry("shared_patients", "Người tôi chăm sóc"),
            Map.entry("result_returns", "Hẹn trả kết quả"),
            Map.entry("feedback", "Góp ý của bệnh nhân"),
            Map.entry("licenses", "Chứng chỉ hành nghề"),
            Map.entry("reports", "Báo cáo tổng hợp"),
            Map.entry("clinical_encounters", "Lần khám"),
            Map.entry("roles", "Vai trò"),
            Map.entry("row_level_policies", "Phạm vi dữ liệu"),
            // Shown as "Cơ sở"; "Phòng khám" is what the issue tracker and most people call one.
            Map.entry("clinics", "Cơ sở phòng khám"));
    private static final Map<String, String> ACTION_LABELS = Map.ofEntries(
            Map.entry("view", "Xem"),
            Map.entry("update", "Cập nhật"),
            Map.entry("role_change", "Đổi vai trò"),
            Map.entry("status_change", "Đổi trạng thái"),
            Map.entry("membership_end", "Kết thúc thành viên"),
            Map.entry("transfer", "Chuyển cơ sở"),
            Map.entry("password_reset", "Đặt lại mật khẩu"),
            Map.entry("delete", "Xoá"),
            Map.entry("assign", "Cấp / gán"),
            Map.entry("return", "Thu hồi"),
            Map.entry("pay", "Thu tiền"),
            Map.entry("create", "Tạo mới"),
            Map.entry("consent", "Đồng ý theo dõi"),
            Map.entry("end", "Kết thúc"),
            Map.entry("export", "Xuất dữ liệu"),
            Map.entry("consult", "Tư vấn"),
            Map.entry("schedule", "Hẹn lịch"),
            Map.entry("reschedule", "Đổi lịch hẹn"),
            Map.entry("arrive", "Đã đến"),
            Map.entry("acknowledge", "Xác nhận"),
            Map.entry("grant", "Cấp quyền"),
            Map.entry("revoke", "Thu hồi quyền"),
            Map.entry("reply", "Phản hồi"));

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

    static Specification<AuditLog> specification(ListParams params) {
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
        params.search().ifPresent(term -> specs.add((root, q, cb) -> {
            // "Device assignments" as shown for a type with no label is stored as device_assignments.
            String code = term.replace(' ', '_');
            var textMatch = cb.or(
                cb.like(cb.lower(root.get("actorName")), ListParams.likePattern(term)),
                cb.like(cb.lower(root.get("entityType")), ListParams.likePattern(code)),
                cb.like(cb.lower(root.get("action")), ListParams.likePattern(code)),
                root.get("entityType").in(labelled(ENTITY_LABELS, term)),
                root.get("action").in(labelled(ACTION_LABELS, term)));
            try {
                long userId = Long.parseLong(term.trim());
                var employees = q.subquery(Long.class);
                var employee = employees.from(com.vn.vitalcare.entity.Employee.class);
                employees.select(employee.get("id"))
                        .where(cb.equal(employee.get("user").get("id"), userId));
                return cb.or(textMatch,
                        cb.equal(root.get("actorId"), userId),
                        cb.and(cb.equal(root.get("entityType"), "users"), cb.equal(root.get("entityId"), userId)),
                        cb.and(cb.equal(root.get("entityType"), "employees"), root.get("entityId").in(employees)));
            } catch (NumberFormatException ignored) {
                return textMatch;
            }
        }));
        return Specification.allOf(specs);
    }

    /**
     * The codes whose label contains the term, ignoring case and tone marks —
     * "Xóa", "Xoá" and "xoa" are one word to the person typing it; a
     * placeholder that matches nothing when none does.
     */
    static List<String> labelled(Map<String, String> labels, String term) {
        String needle = fold(term);
        List<String> codes = labels.entrySet().stream()
                .filter(e -> fold(e.getValue()).contains(needle))
                .map(Map.Entry::getKey)
                .toList();
        // An empty IN list is not valid SQL everywhere.
        return codes.isEmpty() ? List.of("") : codes;
    }

    private static String fold(String text) {
        // NFD leaves đ whole: "doi vai tro" has to find "Đổi vai trò".
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT)
                .replace('đ', 'd');
    }
}
