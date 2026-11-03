package com.vn.vitalcare.care.sharing;

import com.vn.vitalcare.entity.EmergencyNotice;
import com.vn.vitalcare.entity.EscalationCall;
import com.vn.vitalcare.share.audit.AuditEvent;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import com.vn.vitalcare.share.security.CurrentUser;
import com.vn.vitalcare.share.security.Permissions;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/emergency_alerts} — a caregiver's emergency alerts and their
 * acknowledgement (BF-06 Emergency Alert Acknowledgement);
 * {@code /api/escalations} — the clinic's Caregiver Escalation and Call Log.
 */
@RestController
public class EmergencyController {

    private final EmergencyNoticeRepository notices;
    private final EscalationCallRepository calls;
    private final ApplicationEventPublisher events;
    private final com.vn.vitalcare.care.alert.Alerts alerts;
    private final Clock clock;

    public EmergencyController(EmergencyNoticeRepository notices, EscalationCallRepository calls,
                               ApplicationEventPublisher events, com.vn.vitalcare.care.alert.Alerts alerts,
                               Clock clock) {
        this.alerts = alerts;
        this.notices = notices;
        this.calls = calls;
        this.events = events;
        this.clock = clock;
    }

    public record CallResponse(Long id, String channel, String phone, String outcome, String detail,
                               OffsetDateTime placedAt) {
        static CallResponse of(EscalationCall c) {
            return new CallResponse(c.getId(), c.getChannel(), c.getPhone(), c.getOutcome(), c.getDetail(), c.getPlacedAt());
        }
    }

    public record NoticeResponse(Long id, Long alertId, String severity, String message, Long customerId,
                                 String patientName, String emergencyPhone, String status, OffsetDateTime notifiedAt,
                                 OffsetDateTime acknowledgedAt, OffsetDateTime escalatedAt, List<CallResponse> calls) {
        static NoticeResponse of(EmergencyNotice n, List<CallResponse> calls) {
            return new NoticeResponse(n.getId(), n.getAlert().getId(), n.getAlert().getSeverity(),
                    n.getAlert().getMessage(), n.getCustomer().getId(), n.getCustomer().getFullName(),
                    n.getEmergencyPhone(), n.getStatus(), n.getNotifiedAt(), n.getAcknowledgedAt(),
                    n.getEscalatedAt(), calls);
        }
    }

    @GetMapping("/api/emergency_alerts")
    @PreAuthorize("hasAuthority('" + Permissions.EMERGENCY_ALERTS_READ + "')")
    @Transactional(readOnly = true)
    public List<NoticeResponse> mine() {
        return notices.findForRecipient(me(), PageRequest.of(0, 50)).stream()
                .map(n -> NoticeResponse.of(n, List.of())).toList();
    }

    @GetMapping("/api/emergency_alerts/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.EMERGENCY_ALERTS_READ + "')")
    @Transactional(readOnly = true)
    public NoticeResponse one(@PathVariable Long id) {
        EmergencyNotice n = own(id);
        return NoticeResponse.of(n, calls.findForNotice(id).stream().map(CallResponse::of).toList());
    }

    /**
     * "Tôi đã biết": the caregiver has seen it. Before the delay, no call is made; after it, the call
     * already went out and the acknowledgement is still recorded.
     */
    @PostMapping("/api/emergency_alerts/{id}/acknowledge")
    @PreAuthorize("hasAuthority('" + Permissions.EMERGENCY_ALERTS_WRITE + "')")
    @Transactional
    public NoticeResponse acknowledge(@PathVariable Long id) {
        EmergencyNotice n = own(id);
        if (n.getAcknowledgedAt() == null) {
            OffsetDateTime now = OffsetDateTime.now(clock);
            n.setAcknowledgedAt(now);
            if (EmergencyNotice.PENDING.equals(n.getStatus())) {
                n.setStatus(EmergencyNotice.ACKNOWLEDGED);
            }
            notices.save(n);
            events.publishEvent(new AuditEvent("emergency_alerts", id, "acknowledge", null,
                    Map.of("alertId", n.getAlert().getId(), "status", n.getStatus())));
        }
        return NoticeResponse.of(n, calls.findForNotice(id).stream().map(CallResponse::of).toList());
    }

    /** The calls and SMS placed for an alert (staff). */
    @GetMapping("/api/escalations")
    @PreAuthorize("hasAuthority('" + Permissions.ESCALATIONS_READ + "') and hasAuthority('" + Permissions.ALERTS_READ + "')")
    @Transactional(readOnly = true)
    public List<CallResponse> forAlert(@RequestParam Long alertId) {
        // Through the alerts scope first: another clinic's alert (and its emergency numbers) is a 404.
        alerts.get(alertId);
        return calls.findForAlert(alertId).stream().map(CallResponse::of).toList();
    }

    private EmergencyNotice own(Long id) {
        return notices.findWithDetails(id)
                .filter(n -> n.getRecipient().getId().equals(me()))
                .orElseThrow(() -> new ResourceNotFoundException("Emergency alert", id));
    }

    private static Long me() {
        return CurrentUser.id().orElseThrow();
    }
}
