package com.vn.vitalcare.care.alert;

import com.vn.vitalcare.care.customer.service.CustomerService;
import com.vn.vitalcare.entity.Alert;
import com.vn.vitalcare.entity.AlertAction;
import com.vn.vitalcare.entity.Customer;
import com.vn.vitalcare.identity.rowlevel.service.RowLevelSecurity;
import com.vn.vitalcare.share.audit.AuditEvent;
import com.vn.vitalcare.share.data.BaseEntitySpecifications;
import com.vn.vitalcare.share.exception.ConflictException;
import com.vn.vitalcare.share.exception.FieldValidationException;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import com.vn.vitalcare.share.security.CurrentUser;
import com.vn.vitalcare.share.security.rowlevel.Action;
import com.vn.vitalcare.share.web.ListParams;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Alerts and their handling (BF-05 Alert Handling): acknowledge, add notes,
 * resolve with handling notes or dismiss with a reason. Every step goes on the
 * alert's Action History and the audit log.
 */
@Service
@Transactional(readOnly = true)
public class Alerts {

    private static final Set<String> SORTABLE = Set.of("id", "triggeredAt", "lastTriggeredAt", "severity", "status");
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final AlertRepository alerts;
    private final AlertActionRepository actions;
    private final CustomerService customers;
    private final RowLevelSecurity rowLevel;
    private final ApplicationEventPublisher events;
    private final com.vn.vitalcare.care.staff.service.ClinicScope clinicScope;
    private final Clock clock;

    public Alerts(AlertRepository alerts, AlertActionRepository actions, CustomerService customers,
                  RowLevelSecurity rowLevel, ApplicationEventPublisher events,
                  com.vn.vitalcare.care.staff.service.ClinicScope clinicScope, Clock clock) {
        this.clinicScope = clinicScope;
        this.alerts = alerts;
        this.actions = actions;
        this.customers = customers;
        this.rowLevel = rowLevel;
        this.events = events;
        this.clock = clock;
    }

    public Page<Alert> list(ListParams params) {
        return alerts.findAll(rowLevel.scope(Alert.class, Action.READ).and(filter(params)),
                params.pageable(SORTABLE, Sort.by(Sort.Order.desc("triggeredAt"), Sort.Order.desc("id"))));
    }

    /** What still needs somebody, in the caller's scope: for the badge and the critical banner. */
    public java.util.Map<String, Object> summary() {
        Specification<Alert> active = rowLevel.scope(Alert.class, Action.READ).and(BaseEntitySpecifications.notDeleted())
                .and((r, q, cb) -> r.get("status").in(Alert.OPEN, Alert.ACKNOWLEDGED));
        long open = alerts.count(active);
        long critical = alerts.count(active.and((r, q, cb) -> cb.equal(r.get("severity"), Alert.CRITICAL)));
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("clinicId", clinicScope.currentClinicId().map(UUID::toString).orElse(null));
        body.put("active", open);
        body.put("critical", critical);
        return body;
    }

    public Alert get(Long id) {
        return find(id, Action.READ);
    }

    public List<AlertAction> history(Alert alert) {
        return actions.findByAlertIdOrderByOccurredAtAscIdAsc(alert.getId());
    }

    /** Abnormal Reading List: the readings behind the alerts the caller may see, newest first. */
    public Page<AlertAction> abnormalReadings(ListParams params, int start, int end) {
        List<Long> ids = alerts.findAll(rowLevel.scope(Alert.class, Action.READ).and(filter(params)),
                PageRequest.of(0, 2000)).map(Alert::getId).getContent();
        int size = Math.min(Math.max(end - start, 1), 100);
        if (ids.isEmpty()) {
            return Page.empty(PageRequest.of(0, size));
        }
        return actions.findReadings(ids, PageRequest.of(Math.max(start, 0) / size, size));
    }

    /** A patient's own alerts, and their dependants' (Patient Alert History on the portal). */
    public Page<Alert> own(Long profileId, int start, int end) {
        Customer me = customers.getForCurrentUser();
        List<Long> ids = profileId == null
                ? customers.profilesOf(me).stream().map(Customer::getId).toList()
                : List.of(customers.ownProfile(me, profileId).getId());
        int size = Math.min(Math.max(end - start, 1), 100);
        return alerts.findForProfiles(ids, PageRequest.of(Math.max(start, 0) / size, size));
    }

    /** A patient's alerts, unscoped — for a caregiver whose grant the caller already checked. */
    public Page<Alert> forPatient(Long customerId, int start, int end) {
        int size = Math.min(Math.max(end - start, 1), 100);
        return alerts.findForProfiles(List.of(customerId), PageRequest.of(Math.max(start, 0) / size, size));
    }

    @Transactional
    public Alert acknowledge(Long id) {
        Alert alert = lockedForWrite(id);
        if (!Alert.OPEN.equals(alert.getStatus())) {
            throw new ConflictException("Only an open alert can be acknowledged");
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        alert.acknowledge(me(), now);
        return done(alert, AlertAction.ACKNOWLEDGED, null, "acknowledge");
    }

    @Transactional
    public Alert note(Long id, String note) {
        Alert alert = lockedForWrite(id);
        String text = required(note, "note", "Write the note");
        actions.save(new AlertAction(alert, AlertAction.NOTE, OffsetDateTime.now(clock))
                .by(me(), CurrentUser.displayName().orElse(null)).note(text));
        return alert;
    }

    @Transactional
    public Alert resolve(Long id, String notes) {
        Alert alert = lockedForWrite(id);
        if (!alert.isActive()) {
            throw new ConflictException("This alert has already been closed");
        }
        String text = required(notes, "note", "Handling notes are required to resolve an alert");
        alert.resolve(me(), text, OffsetDateTime.now(clock));
        return done(alert, AlertAction.RESOLVED, text, "resolve");
    }

    @Transactional
    public Alert dismiss(Long id, String reason) {
        Alert alert = lockedForWrite(id);
        if (!alert.isActive()) {
            throw new ConflictException("This alert has already been closed");
        }
        String text = required(reason, "note", "A reason is required to dismiss an alert");
        alert.dismiss(me(), text, OffsetDateTime.now(clock));
        return done(alert, AlertAction.DISMISSED, text, "dismiss");
    }

    private Alert done(Alert alert, String action, String note, String audit) {
        rowLevel.checkWritable(alert);
        Alert saved = alerts.persist(alert);
        actions.save(new AlertAction(saved, action, OffsetDateTime.now(clock))
                .by(me(), CurrentUser.displayName().orElse(null)).note(note));
        events.publishEvent(new AuditEvent("alerts", saved.getId(), audit, null,
                Map.of("status", saved.getStatus(), "note", note == null ? "" : note)));
        events.publishEvent(new AlertEvents.Changed(saved.getId(), saved.getClinicId()));
        return saved;
    }

    /** The patient's alert lock first, then the scoped read: what is checked is what the engine cannot change. */
    private Alert lockedForWrite(Long id) {
        alerts.customerOf(id).ifPresent(alerts::lockPatient);
        return find(id, Action.WRITE);
    }

    private Alert find(Long id, Action action) {
        return alerts.findOne(Specification.allOf(rowLevel.byId(Alert.class, action, id),
                        BaseEntitySpecifications.notDeleted()))
                .orElseThrow(() -> new ResourceNotFoundException("Alert", id));
    }

    private static Long me() {
        return CurrentUser.id().orElse(null);
    }

    private static String required(String value, String field, String message) {
        if (value == null || value.isBlank()) {
            throw new FieldValidationException(field, message);
        }
        String t = value.trim();
        return t.length() <= 4000 ? t : t.substring(0, 4000);
    }

    private static Specification<Alert> filter(ListParams params) {
        List<Specification<Alert>> specs = new ArrayList<>();
        specs.add(BaseEntitySpecifications.notDeleted());
        for (ListParams.Criterion c : params.filters()) {
            String v = c.value() == null ? "" : c.value().trim();
            try {
                switch (c.field()) {
                    // "active" = open or acknowledged: what still needs somebody.
                    case "status" -> specs.add("active".equalsIgnoreCase(v)
                            ? (r, q, cb) -> r.get("status").in(Alert.OPEN, Alert.ACKNOWLEDGED)
                            : (r, q, cb) -> cb.equal(r.get("status"), v.toUpperCase(Locale.ROOT)));
                    case "severity", "metric" -> specs.add((r, q, cb) -> cb.equal(r.get(c.field()), v.toUpperCase(Locale.ROOT)));
                    case "customerId" -> {
                        Long id = Long.valueOf(v);
                        specs.add((r, q, cb) -> cb.equal(r.get("customer").get("id"), id));
                    }
                    case "triggeredFrom", "triggeredTo" -> {
                        LocalDate day = LocalDate.parse(v.substring(0, 10));
                        if (c.field().equals("triggeredFrom")) {
                            OffsetDateTime start = day.atStartOfDay(ZONE).toOffsetDateTime();
                            specs.add((r, q, cb) -> cb.greaterThanOrEqualTo(r.get("triggeredAt"), start));
                        } else {
                            OffsetDateTime end = day.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime();
                            specs.add((r, q, cb) -> cb.lessThan(r.get("triggeredAt"), end));
                        }
                    }
                    default -> {
                    }
                }
            } catch (NumberFormatException | DateTimeParseException | StringIndexOutOfBoundsException e) {
                specs.add((r, q, cb) -> cb.disjunction());
            }
        }
        params.search().ifPresent(term -> specs.add((r, q, cb) -> cb.or(
                cb.like(cb.lower(r.join("customer").get("fullName")), ListParams.likePattern(term)),
                cb.like(cb.lower(r.get("message")), ListParams.likePattern(term)))));
        return Specification.allOf(specs);
    }
}
