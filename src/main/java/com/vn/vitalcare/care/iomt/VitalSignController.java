package com.vn.vitalcare.care.iomt;

import com.vn.vitalcare.entity.Customer;
import com.vn.vitalcare.entity.VitalSigns;
import com.vn.vitalcare.identity.audit.AuditTrail;
import com.vn.vitalcare.share.security.Permissions;
import com.vn.vitalcare.share.web.ListResponse;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/vital_signs} — staff reading patients' vital signs (FE-04):
 * the monitored patients' overview, one patient's latest values, history and
 * trend, and one measurement. A patient outside the caller's scope is a 404.
 */
@RestController
@RequestMapping("/api/vital_signs")
@PreAuthorize("hasAuthority('" + Permissions.VITAL_SIGNS_READ + "')")
public class VitalSignController {

    private final VitalViews views;
    private final AuditTrail audit;

    public VitalSignController(VitalViews views, AuditTrail audit) {
        this.views = views;
        this.audit = audit;
    }

    /** Vital Sign Monitoring Overview: who wears a device now, and their latest values. */
    @GetMapping("/overview")
    public List<VitalViewDtos.Monitored> overview() {
        return views.monitored();
    }

    /** Who a patient's vital-sign page is about — for staff who read readings but not the customer records. */
    @GetMapping("/patients/{customerId}")
    public VitalViewDtos.Patient patient(@PathVariable Long customerId) {
        return VitalViewDtos.Patient.of(views.patient(customerId));
    }

    @GetMapping("/latest")
    public VitalViewDtos.Latest latest(@RequestParam Long customerId) {
        return views.latest(views.patient(customerId));
    }

    /** Vital Sign History: {@code customerId}, {@code from}/{@code to} (ISO-8601), simple-rest paging. */
    @GetMapping
    public ResponseEntity<List<VitalViewDtos.Reading>> history(
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(name = "validation", required = false) String validation,
            @RequestParam(name = "_start", defaultValue = "0") int start,
            @RequestParam(name = "_end", defaultValue = "50") int end) {
        Customer patient = views.patient(customerId);
        Page<VitalSigns> page = views.history(patient, from, to, "all".equals(validation), start, end);
        // Health data is protected: who looked at whose readings is on record (BR-24).
        viewed(patient.getId(), "history", from, to);
        return ListResponse.of(page.getContent().stream().map(VitalViewDtos.Reading::of).toList(), page.getTotalElements());
    }

    /** Trend & Visualization: min/avg/max per hour (≤ 2 days) or per day. */
    @GetMapping("/trend")
    public VitalViewDtos.Trend trend(@RequestParam Long customerId, @RequestParam(required = false) String from,
                                     @RequestParam(required = false) String to) {
        VitalViewDtos.Trend trend = views.trend(views.patient(customerId), from, to);
        viewed(customerId, "trend", trend.from().toString(), trend.to().toString());
        return trend;
    }

    /** Measurement Details. */
    @GetMapping("/{id}")
    public VitalViewDtos.Detail get(@PathVariable Long id) {
        VitalViewDtos.Detail detail = VitalViewDtos.Detail.of(views.one(id, views::patient));
        viewed(detail.customerId(), "reading " + id, null, null);
        return detail;
    }

    private void viewed(Long customerId, String what, String from, String to) {
        Map<String, Object> seen = new java.util.LinkedHashMap<>();
        seen.put("what", what);
        if (from != null) {
            seen.put("from", from);
        }
        if (to != null) {
            seen.put("to", to);
        }
        audit.record("vital_signs", customerId, "view", null, seen);
    }
}
