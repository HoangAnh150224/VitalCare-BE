package com.vn.vitalcare.care.reports;

import com.vn.vitalcare.share.audit.AuditEvent;
import com.vn.vitalcare.share.security.Permissions;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/monitoring_dashboard} (BF-10) and {@code /api/reports/{kind}} (BF-08). */
@RestController
public class ReportsController {

    private final Reports reports;
    private final ApplicationEventPublisher events;

    public ReportsController(Reports reports, ApplicationEventPublisher events) {
        this.reports = reports;
        this.events = events;
    }

    @GetMapping("/api/monitoring_dashboard")
    @PreAuthorize("hasAuthority('" + Permissions.MONITORING_DASHBOARD_READ + "')")
    public Map<String, Object> dashboard(@RequestParam(required = false) UUID clinicId) {
        return reports.dashboard(clinicId);
    }

    @GetMapping("/api/reports/{kind}")
    @PreAuthorize("hasAuthority('" + Permissions.REPORTS_READ + "')")
    public Map<String, Object> report(@PathVariable Reports.Kind kind,
                                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                      @RequestParam(required = false) UUID clinicId) {
        return reports.report(kind, from, to, clinicId);
    }

    /**
     * Records an export (BF-08 Report Export, held to {@code reports:export});
     * the page builds the CSV from the report it already shows, and asks here first.
     */
    @PostMapping("/api/reports/{kind}/export")
    @PreAuthorize("hasAuthority('" + Permissions.REPORTS_EXPORT + "')")
    public Map<String, Object> export(@PathVariable Reports.Kind kind,
                                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                      @RequestParam(required = false) UUID clinicId) {
        Map<String, Object> report = reports.report(kind, from, to, clinicId);
        Object clinic = report.get("clinicId");
        events.publishEvent(new AuditEvent("reports", null, "export", null, Map.of("kind", kind.name(),
                "from", from.toString(), "to", to.toString(), "clinicId", clinic == null ? "all" : clinic.toString())));
        return report;
    }
}
