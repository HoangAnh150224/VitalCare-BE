package com.vn.vitalcare.care.sharing;

import com.vn.vitalcare.care.alert.AlertEvents;
import com.vn.vitalcare.care.assignment.repository.MonitoringAssignmentRepository;
import com.vn.vitalcare.care.notification.Notifier;
import com.vn.vitalcare.entity.Alert;
import com.vn.vitalcare.entity.AlertAction;
import com.vn.vitalcare.entity.EmergencyNotice;
import com.vn.vitalcare.entity.EscalationCall;
import com.vn.vitalcare.entity.MonitoringAssignment;
import com.vn.vitalcare.entity.NotificationLevel;
import com.vn.vitalcare.entity.SharingPermission;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Emergency alerts to caregivers and their escalation (BF-06).
 *
 * <p>A critical alert, new or raised to critical, is told to every caregiver
 * whose grant in force covers alerts: a notification of type
 * {@code emergency}, which the portal and the app pop up. If it is not
 * acknowledged within {@code app.escalation.delay} (30 s), the emergency
 * number on the grant gets a call and an SMS; each is written to the call log,
 * the alert's history and the care team is told.
 * ponytail: one sweeping node; rows are taken with SKIP LOCKED, so two would not call twice.
 */
@Component
public class EmergencyEscalation {

    private final SharingPermissionRepository permissions;
    private final EmergencyNoticeRepository notices;
    private final EscalationCallRepository calls;
    private final com.vn.vitalcare.care.alert.AlertRepository alerts;
    private final com.vn.vitalcare.care.alert.AlertActionRepository actions;
    private final MonitoringAssignmentRepository careTeams;
    private final EmergencyChannels channels;
    private final Notifier notifier;
    private final Clock clock;
    private final Duration delay;

    public EmergencyEscalation(SharingPermissionRepository permissions, EmergencyNoticeRepository notices,
                               EscalationCallRepository calls, com.vn.vitalcare.care.alert.AlertRepository alerts,
                               com.vn.vitalcare.care.alert.AlertActionRepository actions,
                               MonitoringAssignmentRepository careTeams, EmergencyChannels channels, Notifier notifier,
                               Clock clock, @Value("${app.escalation.delay:30s}") Duration delay) {
        this.permissions = permissions;
        this.notices = notices;
        this.calls = calls;
        this.alerts = alerts;
        this.actions = actions;
        this.careTeams = careTeams;
        this.channels = channels;
        this.notifier = notifier;
        this.clock = clock;
        this.delay = delay;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(AlertEvents.Raised raised) {
        if (!Alert.CRITICAL.equals(raised.severity())) {
            return;
        }
        Alert alert = alerts.findForNotice(raised.alertId()).orElse(null);
        if (alert == null) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        for (SharingPermission p : permissions.findInForceOf(raised.customerId(), now)) {
            if (!p.isShareAlerts() || notices.existsByAlertIdAndRecipientId(alert.getId(), p.getCaregiver().getId())) {
                continue;
            }
            EmergencyNotice n = new EmergencyNotice();
            n.setAlert(alert);
            n.setPermission(p);
            n.setCustomer(alert.getCustomer());
            n.setRecipient(p.getCaregiver());
            n.setEmergencyPhone(p.getEmergencyPhone());
            n.setNotifiedAt(now);
            n.setStatus(EmergencyNotice.PENDING);
            EmergencyNotice saved = notices.save(n);
            notifier.notify(p.getCaregiver().getId(), NotificationLevel.ERROR, "emergency",
                    "KHẨN: " + alert.getCustomer().getFullName(),
                    alert.getMessage() + ". Bấm \"Tôi đã biết\" trong " + delay.toSeconds()
                            + " giây, nếu không hệ thống sẽ gọi số khẩn cấp " + p.getEmergencyPhone() + ".",
                    "/emergency/" + saved.getId());
            actions.save(new AlertAction(alert, AlertAction.NOTIFIED, now)
                    .note("Đã báo khẩn cho người chăm sóc " + p.getCaregiver().getFullName()));
        }
    }

    /** Every 5 s: emergencies left unacknowledged past the delay are called and texted. */
    @Scheduled(fixedDelayString = "${app.escalation.sweep:5s}", initialDelayString = "${app.escalation.sweep:5s}")
    @Transactional
    public void sweep() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        for (EmergencyNotice n : notices.findDue(now.minus(delay), PageRequest.of(0, 50))) {
            escalate(n, now);
        }
    }

    void escalate(EmergencyNotice n, OffsetDateTime now) {
        Alert alert = n.getAlert();
        n.setStatus(EmergencyNotice.ESCALATED);
        n.setEscalatedAt(now);
        String phone = n.getEmergencyPhone();
        String patient = n.getCustomer().getFullName();
        String spoken = "VitalCare cảnh báo khẩn: " + alert.getMessage() + " của " + patient + ".";
        record(n, alert, EscalationCall.CALL, phone, channels.call(phone, spoken), now);
        // eSMS without Unicode: the SMS goes out unaccented.
        String text = "VitalCare KHAN CAP: " + plain(patient) + " co chi so nguy hiem (" + plain(alert.getMessage())
                + "). Vui long lien he ngay hoac goi 115.";
        record(n, alert, EscalationCall.SMS, phone, channels.sms(phone, text), now);
        actions.save(new AlertAction(alert, AlertAction.ESCALATED, now).note("Người chăm sóc "
                + n.getRecipient().getFullName() + " chưa xác nhận sau " + delay.toSeconds()
                + " giây: đã gọi và nhắn tin tới " + phone));
        for (MonitoringAssignment a : careTeams.findByCustomerIdAndUnassignedAtIsNullAndDeletedAtIsNull(n.getCustomer().getId())) {
            if (a.getEmployee() != null && a.getEmployee().getUser() != null) {
                notifier.notify(a.getEmployee().getUser().getId(), NotificationLevel.ERROR, "alert",
                        "Đã gọi khẩn cho người chăm sóc của " + patient,
                        "Người chăm sóc chưa xác nhận cảnh báo khẩn trong " + delay.toSeconds() + " giây.",
                        "/alerts/show/" + alert.getId());
            }
        }
        notices.save(n);
    }

    private void record(EmergencyNotice n, Alert alert, String channel, String phone, EmergencyChannels.Outcome o,
                        OffsetDateTime now) {
        EscalationCall c = new EscalationCall();
        c.setNotice(n);
        c.setAlert(alert);
        c.setChannel(channel);
        c.setPhone(phone);
        c.setOutcome(o.outcome());
        c.setDetail(o.detail() == null || o.detail().length() <= 500 ? o.detail() : o.detail().substring(0, 500));
        c.setPlacedAt(now);
        calls.save(c);
    }

    /** Without accents, as an SMS without Unicode carries it. */
    static String plain(String s) {
        String d = java.text.Normalizer.normalize(s == null ? "" : s, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").replace('đ', 'd').replace('Đ', 'D');
        return d.replaceAll("[^\\x20-\\x7E]", "");
    }
}
