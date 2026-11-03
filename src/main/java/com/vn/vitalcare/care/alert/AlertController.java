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

    public AlertController(AlertRules rules, Alerts alerts) {
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
    public ResponseEntity<List<AlertDtos.AbnormalReading>> readings(
            @RequestParam MultiValueMap<String, String> query,
            @RequestParam(name = "_start", defaultValue = "0") int start,
            @RequestParam(name = "_end", defaultValue = "20") int end) {
        Page<AlertAction> page = alerts.abnormalReadings(new ListParams(query), start, end);
        return ListResponse.of(page.getContent().stream().map(AlertDtos.AbnormalReading::of).toList(), page.getTotalElements());
    }

    @GetMapping("/alerts/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.ALERTS_READ + "')")
    public AlertDtos.AlertDetail alert(@PathVariable Long id) {
        return detail(alerts.get(id));
    }

    @PostMapping("/alerts/{id}/acknowledge")
    @PreAuthorize("hasAuthority('" + Permissions.ALERTS_WRITE + "')")
    public AlertDtos.AlertDetail acknowledge(@PathVariable Long id) {
        return detail(alerts.acknowledge(id));
    }

    @PostMapping("/alerts/{id}/notes")
    @PreAuthorize("hasAuthority('" + Permissions.ALERTS_WRITE + "')")
    public AlertDtos.AlertDetail note(@PathVariable Long id, @Valid @RequestBody AlertDtos.Note body) {
        return detail(alerts.note(id, body.note()));
    }

    @PostMapping("/alerts/{id}/resolve")
    @PreAuthorize("hasAuthority('" + Permissions.ALERTS_WRITE + "')")
    public AlertDtos.AlertDetail resolve(@PathVariable Long id, @Valid @RequestBody AlertDtos.Note body) {
        return detail(alerts.resolve(id, body.note()));
    }

    @PostMapping("/alerts/{id}/dismiss")
    @PreAuthorize("hasAuthority('" + Permissions.ALERTS_WRITE + "')")
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

    private AlertDtos.AlertDetail detail(Alert alert) {
        return new AlertDtos.AlertDetail(AlertDtos.AlertResponse.of(alert), alert.getHandlingNotes(),
                alert.getDismissReason(), alerts.history(alert).stream().map(AlertDtos.ActionResponse::of).toList());
    }
}
