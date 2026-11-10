package com.vn.vitalcare.care.alert;

import com.vn.vitalcare.care.customer.service.CustomerService;
import com.vn.vitalcare.care.staff.service.ClinicScope;
import com.vn.vitalcare.entity.AlertRule;
import com.vn.vitalcare.share.audit.AuditEvent;
import com.vn.vitalcare.share.data.BaseEntitySpecifications;
import com.vn.vitalcare.share.exception.FieldValidationException;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import com.vn.vitalcare.share.security.CurrentUser;
import com.vn.vitalcare.share.security.rowlevel.Action;
import com.vn.vitalcare.identity.rowlevel.service.RowLevelSecurity;
import com.vn.vitalcare.share.web.ListParams;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Alert rules (FE-05 Alert Rule List / Create / Details / Update), by clinic. */
@Service
@Transactional(readOnly = true)
public class AlertRules {

    private static final Set<String> SORTABLE = Set.of("id", "metric", "severity", "threshold", "name");
    private static final Set<String> METRICS = Set.of("HEART_RATE", "SPO2", "SKIN_TEMPERATURE", "BODY_TEMPERATURE");
    /** A threshold outside what the band can measure would never fire, or always would. */
    private static final Map<String, BigDecimal[]> RANGES = Map.of(
            "HEART_RATE", new BigDecimal[] {new BigDecimal("20"), new BigDecimal("250")},
            "SPO2", new BigDecimal[] {new BigDecimal("50"), new BigDecimal("100")},
            "SKIN_TEMPERATURE", new BigDecimal[] {new BigDecimal("25"), new BigDecimal("45")},
            "BODY_TEMPERATURE", new BigDecimal[] {new BigDecimal("34"), new BigDecimal("43")});

    private final AlertRuleRepository rules;
    private final CustomerService customers;
    private final ClinicScope clinicScope;
    private final RowLevelSecurity rowLevel;
    private final ApplicationEventPublisher events;

    public AlertRules(AlertRuleRepository rules, CustomerService customers, ClinicScope clinicScope,
                      RowLevelSecurity rowLevel, ApplicationEventPublisher events, Clock clock) {
        this.rules = rules;
        this.customers = customers;
        this.clinicScope = clinicScope;
        this.rowLevel = rowLevel;
        this.events = events;
    }

    public Page<AlertRule> list(ListParams params) {
        List<Specification<AlertRule>> specs = new ArrayList<>();
        specs.add(rowLevel.scope(AlertRule.class, Action.READ));
        specs.add(BaseEntitySpecifications.notDeleted());
        for (ListParams.Criterion c : params.filters()) {
            String v = c.value() == null ? "" : c.value().trim();
            switch (c.field()) {
                case "metric", "severity" -> specs.add((r, q, cb) -> cb.equal(r.get(c.field()), v.toUpperCase(Locale.ROOT)));
                case "enabled" -> specs.add((r, q, cb) -> cb.equal(r.get("enabled"), Boolean.parseBoolean(v)));
                // "clinic" for the clinic-wide rules, "patient" for patients' own.
                case "scope" -> specs.add((r, q, cb) -> "patient".equals(v) ? cb.isNotNull(r.get("customer"))
                        : cb.isNull(r.get("customer")));
                case "customerId" -> {
                    try {
                        Long id = Long.valueOf(v);
                        specs.add((r, q, cb) -> cb.equal(r.get("customer").get("id"), id));
                    } catch (NumberFormatException e) {
                        specs.add((r, q, cb) -> cb.disjunction());
                    }
                }
                default -> {
                }
            }
        }
        params.search().ifPresent(term -> specs.add((r, q, cb) -> cb.like(cb.lower(r.get("name")), ListParams.likePattern(term))));
        return rules.findAll(Specification.allOf(specs), params.pageable(SORTABLE,
                Sort.by(Sort.Order.asc("metric"), Sort.Order.asc("operator"), Sort.Order.asc("threshold"))));
    }

    public AlertRule get(Long id) {
        return find(id, Action.READ);
    }

    @Transactional
    public AlertRule create(AlertDtos.RuleRequest request) {
        AlertRule rule = new AlertRule();
        apply(rule, request, true);
        AlertRule saved = rules.persist(rule);
        rules.flush();
        rowLevel.checkWritable(saved);
        events.publishEvent(new AuditEvent("alert_rules", saved.getId(), "create", null, state(saved)));
        return saved;
    }

    @Transactional
    public AlertRule update(Long id, AlertDtos.RuleRequest request) {
        AlertRule rule = find(id, Action.WRITE);
        Map<String, Object> before = state(rule);
        apply(rule, request, false);
        rowLevel.checkWritable(rule);
        AlertRule saved = rules.persist(rule);
        events.publishEvent(new AuditEvent("alert_rules", saved.getId(), "update", before, state(saved)));
        return saved;
    }

    @Transactional
    public void delete(Long id) {
        AlertRule rule = find(id, Action.WRITE);
        rule.markDeleted(CurrentUser.id().orElse(null), java.time.Instant.now());
        rules.persist(rule);
        events.publishEvent(new AuditEvent("alert_rules", id, "delete", state(rule), null));
    }

    private void apply(AlertRule rule, AlertDtos.RuleRequest r, boolean creating) {
        String metric = upper(r.metric());
        if (!METRICS.contains(metric)) {
            throw new FieldValidationException("metric", "Choose heart rate, SpO2, skin or body temperature");
        }
        String operator = upper(r.operator());
        if (!AlertRule.BELOW.equals(operator) && !AlertRule.ABOVE.equals(operator)) {
            throw new FieldValidationException("operator", "Choose below or above");
        }
        String severity = upper(r.severity());
        if (!"WARNING".equals(severity) && !"CRITICAL".equals(severity)) {
            throw new FieldValidationException("severity", "Choose warning or critical");
        }
        BigDecimal[] range = RANGES.get(metric);
        if (r.threshold().compareTo(range[0]) < 0 || r.threshold().compareTo(range[1]) > 0) {
            throw new FieldValidationException("threshold", "Between %s and %s for this measure"
                    .formatted(range[0].toPlainString(), range[1].toPlainString()));
        }
        rule.setName(r.name().trim());
        rule.setMetric(metric);
        rule.setOperator(operator);
        rule.setThreshold(r.threshold());
        rule.setSeverity(severity);
        rule.setNote(r.note() == null || r.note().isBlank() ? null : r.note().trim());
        if (r.enabled() != null) {
            rule.setEnabled(r.enabled());
        }
        if (creating) {
            // A patient's own rule: only for a patient the caller may see.
            if (r.customerId() != null) {
                rule.setCustomer(customers.get(r.customerId()));
            }
            UUID clinic = clinicScope.currentClinicId().orElse(r.clinicId());
            if (clinic == null) {
                throw new FieldValidationException("clinicId", "Choose a clinic");
            }
            rule.setClinicId(clinic);
        }
    }

    private AlertRule find(Long id, Action action) {
        return rules.findOne(Specification.allOf(rowLevel.byId(AlertRule.class, action, id),
                        BaseEntitySpecifications.notDeleted()))
                .orElseThrow(() -> new ResourceNotFoundException("Alert rule", id));
    }

    private static String upper(String v) {
        return v == null ? "" : v.trim().toUpperCase(Locale.ROOT);
    }

    private static Map<String, Object> state(AlertRule r) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("metric", r.getMetric());
        m.put("operator", r.getOperator());
        m.put("threshold", r.getThreshold());
        m.put("severity", r.getSeverity());
        m.put("enabled", r.isEnabled());
        m.put("customerId", r.getCustomer() == null ? null : r.getCustomer().getId());
        return m;
    }
}
