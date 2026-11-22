package com.vn.vitalcare.care.alert;

import com.vn.vitalcare.entity.Alert;
import com.vn.vitalcare.entity.AlertAction;
import com.vn.vitalcare.entity.AlertRule;
import com.vn.vitalcare.share.security.Permissions;
import com.vn.vitalcare.share.web.ListParams;
import com.vn.vitalcare.share.web.ListResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * FE-05: {@code /api/alert_rules} — the thresholds; {@code /api/alerts} — what
 * they raised and how it was handled; {@code /api/alerts/readings} — the
 * abnormal readings behind them; {@code /api/my_alerts} — a patient's own.
 */
@RestController
@RequestMapping("/api")
public class AlertController {

    private final AlertRules rules;
    private final Alerts alerts;
    private final com.vn.vitalcare.care.iomt.VitalSignsRepository vitals;
    private final com.vn.vitalcare.identity.audit.AuditTrail audit;

    public AlertController(AlertRules rules, Alerts alerts, com.vn.vitalcare.care.iomt.VitalSignsRepository vitals,
                           com.vn.vitalcare.identity.audit.AuditTrail audit) {
        this.vitals = vitals;
        this.audit = audit;
        this.rules = rules;
        this.alerts = alerts;
    }

    // ---- rules ------------------------------------------------------------------------

    @GetMapping("/alert_rules")
    @PreAuthorize("hasAuthority('" + Permissions.ALERT_RULES_READ + "')")
    public ResponseEntity<List<AlertDtos.RuleResponse>> rules(@RequestParam MultiValueMap<String, String> query) {
        Page<AlertRule> page = rules.list(new ListParams(query));
        return ListResponse.of(page.getContent().stream().map(AlertDtos.RuleResponse::of).toList(), page.getTotalElements());
    }

    @GetMapping("/alert_rules/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.ALERT_RULES_READ + "')")
    public AlertDtos.RuleResponse rule(@PathVariable Long id) {
        return AlertDtos.RuleResponse.of(rules.get(id));
    }

    @PostMapping("/alert_rules")
    @PreAuthorize("hasAuthority('" + Permissions.ALERT_RULES_WRITE + "')")
    public ResponseEntity<AlertDtos.RuleResponse> create(@Valid @RequestBody AlertDtos.RuleRequest request) {
        AlertRule created = rules.create(request);
        return ResponseEntity.created(URI.create("/api/alert_rules/" + created.getId()))
                .body(AlertDtos.RuleResponse.of(created));
    }

    @RequestMapping(value = "/alert_rules/{id}", method = {RequestMethod.PUT, RequestMethod.PATCH})
    @PreAuthorize("hasAuthority('" + Permissions.ALERT_RULES_WRITE + "')")
    public AlertDtos.RuleResponse update(@PathVariable Long id, @Valid @RequestBody AlertDtos.RuleRequest request) {
        return AlertDtos.RuleResponse.of(rules.update(id, request));
    }

    @DeleteMapping("/alert_rules/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.ALERT_RULES_WRITE + "')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        rules.delete(id);
        return ResponseEntity.noContent().build();
    }

    // ---- alerts -----------------------------------------------------------------------

    @GetMapping("/alerts")
    @PreAuthorize("hasAuthority('" + Permissions.ALERTS_READ + "')")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ResponseEntity<List<AlertDtos.AlertResponse>> alerts(@RequestParam MultiValueMap<String, String> query) {
        Page<Alert> page = alerts.list(new ListParams(query));
        return ListResponse.of(page.getContent().stream().map(AlertDtos.AlertResponse::of).toList(), page.getTotalElements());
    }

    @GetMapping("/alerts/summary")
    @PreAuthorize("hasAuthority('" + Permissions.ALERTS_READ + "')")
    public java.util.Map<String, Object> summary() {
        return alerts.summary();
    }

    @GetMapping("/alerts/readings")
    @PreAuthorize("hasAuthority('" + Permissions.ALERTS_READ + "')")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ResponseEntity<List<AlertDtos.AbnormalReading>> readings(
            @RequestParam MultiValueMap<String, String> query,
            @RequestParam(name = "_start", defaultValue = "0") int start,
            @RequestParam(name = "_end", defaultValue = "20") int end) {
        Page<AlertAction> page = alerts.abnormalReadings(new ListParams(query), start, end);
        return ListResponse.of(page.getContent().stream().map(AlertDtos.AbnormalReading::of).toList(), page.getTotalElements());
    }

    @GetMapping("/alerts/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.ALERTS_READ + "')")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public AlertDtos.AlertDetail alert(@PathVariable Long id) {
        return detail(alerts.get(id));
    }

    /**
     * The patient's latest readings from the alert's device, newest first, so whoever opens the alert watches
     * the measures move while deciding. Seen by whoever may see the alert itself.
     *
     * <p>Only valid readings: a sensor fault (SpO2 30, a pulse of 0) is not the patient, and the history
     * hides it the same way. Watching them is a look at health data, so it is on record (BR-24) like
     * {@code /vital_signs} — once per viewer and alert every {@link #LIVE_AUDIT_EVERY}, not once per poll.
     */
    @GetMapping("/alerts/{id}/live")
    @PreAuthorize("hasAuthority('" + Permissions.ALERTS_READ + "')")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public List<AlertDtos.LiveReading> live(@PathVariable Long id) {
        com.vn.vitalcare.entity.Alert alert = alerts.get(id);
        if (alert.getDevice() == null) {
            return List.of();
        }
        Long patient = alert.getCustomer().getId();
        recordLiveView(id, patient);
        return vitals.findByDeviceIdOrderByMeasuredAtDescIdDesc(alert.getDevice().getId(),
                        org.springframework.data.domain.PageRequest.of(0, 30))
                .stream()
                .filter(v -> v.getCustomer() != null && patient.equals(v.getCustomer().getId()))
                .filter(com.vn.vitalcare.entity.VitalSigns::isValid)
                .map(v -> new AlertDtos.LiveReading(v.getId(), v.getMeasuredAt(), v.getHeartRate(), v.getSpo2(),
                        v.getBodyTemperature(), v.getTemperature()))
                .toList();
    }

    @PostMapping("/alerts/{id}/acknowledge")
    @PreAuthorize("hasAuthority('" + Permissions.ALERTS_WRITE + "')")
    @org.springframework.transaction.annotation.Transactional
    public AlertDtos.AlertDetail acknowledge(@PathVariable Long id) {
        return detail(alerts.acknowledge(id));
    }

    @PostMapping("/alerts/{id}/notes")
    @PreAuthorize("hasAuthority('" + Permissions.ALERTS_WRITE + "')")
    @org.springframework.transaction.annotation.Transactional
    public AlertDtos.AlertDetail note(@PathVariable Long id, @Valid @RequestBody AlertDtos.Note body) {
        return detail(alerts.note(id, body.note()));
    }

    @PostMapping("/alerts/{id}/resolve")
    @PreAuthorize("hasAuthority('" + Permissions.ALERTS_WRITE + "')")
    @org.springframework.transaction.annotation.Transactional
    public AlertDtos.AlertDetail resolve(@PathVariable Long id, @Valid @RequestBody AlertDtos.Note body) {
        return detail(alerts.resolve(id, body.note()));
    }

    @PostMapping("/alerts/{id}/dismiss")
    @PreAuthorize("hasAuthority('" + Permissions.ALERTS_WRITE + "')")
    @org.springframework.transaction.annotation.Transactional
    public AlertDtos.AlertDetail dismiss(@PathVariable Long id, @Valid @RequestBody AlertDtos.Note body) {
        return detail(alerts.dismiss(id, body.note()));
    }

    // ---- the patient's own -------------------------------------------------------------

    @GetMapping("/my_alerts")
    @PreAuthorize("hasAuthority('" + Permissions.MY_ALERTS_READ + "')")
    public ResponseEntity<List<AlertDtos.AlertResponse>> mine(
            @RequestParam(required = false) Long profileId,
            @RequestParam(name = "_start", defaultValue = "0") int start,
            @RequestParam(name = "_end", defaultValue = "20") int end) {
        Page<Alert> page = alerts.own(profileId, start, end);
        return ListResponse.of(page.getContent().stream().map(AlertDtos.AlertResponse::of).toList(), page.getTotalElements());
    }

    private static final java.time.Duration LIVE_AUDIT_EVERY = java.time.Duration.ofMinutes(10);

    // ponytail: in-memory, per instance — a second instance records its own; a shared store if it ever scales out.
    private final java.util.Map<String, java.time.Instant> liveAudited = new java.util.concurrent.ConcurrentHashMap<>();

    private void recordLiveView(Long alertId, Long patientId) {
        java.time.Instant now = java.time.Instant.now();
        String key = com.vn.vitalcare.share.security.CurrentUser.id().orElse(null) + ":" + alertId;
        java.time.Instant last = liveAudited.get(key);
        if (last != null && last.plus(LIVE_AUDIT_EVERY).isAfter(now)) {
            return;
        }
        liveAudited.put(key, now);
        if (liveAudited.size() > 10_000) {
            liveAudited.values().removeIf(at -> at.plus(LIVE_AUDIT_EVERY).isBefore(now));
        }
        audit.record("vital_signs", patientId, "view", null, java.util.Map.of("what", "alert " + alertId + " live"));
    }

    /**
     * Called inside the endpoint's transaction: the patient's name may come from their account, a lazy
     * association, and open-in-view is off (DEF-ALR-01).
     */
    private AlertDtos.AlertDetail detail(Alert alert) {
        return new AlertDtos.AlertDetail(AlertDtos.AlertResponse.of(alert), alert.getHandlingNotes(),
                alert.getDismissReason(), alerts.history(alert).stream().map(AlertDtos.ActionResponse::of).toList());
    }
}
