package com.vn.vitalcare.care.alert;

import com.vn.vitalcare.care.assignment.repository.MonitoringAssignmentRepository;
import com.vn.vitalcare.care.notification.Notifier;
import com.vn.vitalcare.entity.Alert;
import com.vn.vitalcare.entity.AlertAction;
import com.vn.vitalcare.entity.Customer;
import com.vn.vitalcare.entity.MonitoringAssignment;
import com.vn.vitalcare.entity.NotificationLevel;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Who hears of an alert (BF-05 Alert Notification): the staff looking after
 * the patient, at the alert's level; for a critical one, the patient's own
 * account too (their caregivers come with BF-06). Open alert screens of the
 * clinic are nudged on {@code /topic/alerts/{clinicId}} — the clinic and the
 * severity only; the screens re-read through their scope.
 */
@Component
public class AlertNotifications {

    /** {@code /topic/alerts/{clinicId}}: the first segment is the permission a subscriber needs. */
    public static final String TOPIC = "/topic/alerts/";

    private final AlertRepository alerts;
    private final AlertActionRepository actions;
    private final MonitoringAssignmentRepository careTeams;
    private final Notifier notifier;
    private final SimpMessagingTemplate messaging;
    private final Clock clock;

    public AlertNotifications(AlertRepository alerts, AlertActionRepository actions,
                              MonitoringAssignmentRepository careTeams, Notifier notifier,
                              SimpMessagingTemplate messaging, Clock clock) {
        this.alerts = alerts;
        this.actions = actions;
        this.careTeams = careTeams;
        this.notifier = notifier;
        this.messaging = messaging;
        this.clock = clock;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(AlertEvents.Raised raised) {
        Alert alert = alerts.findForNotice(raised.alertId()).orElse(null);
        if (alert == null) {
            return;
        }
        Customer patient = alert.getCustomer();
        boolean critical = Alert.CRITICAL.equals(alert.getSeverity());
        NotificationLevel level = critical ? NotificationLevel.ERROR : NotificationLevel.WARNING;
        String title = (critical ? "Cảnh báo khẩn: " : "Cảnh báo: ") + patient.getFullName();
        String link = "/alerts/show/" + alert.getId();

        Set<Long> staff = new LinkedHashSet<>();
        for (MonitoringAssignment a : careTeams.findByCustomerIdAndUnassignedAtIsNullAndDeletedAtIsNull(patient.getId())) {
            if (a.getEmployee() != null && a.getEmployee().getUser() != null) {
                staff.add(a.getEmployee().getUser().getId());
            }
        }
        staff.forEach(userId -> notifier.notify(userId, level, "alert", title, alert.getMessage(), link));

        int told = staff.size();
        if (critical) {
            Long own = accountOf(patient);
            if (own != null) {
                notifier.notify(own, level, "alert", "Chỉ số sức khỏe bất thường",
                        alert.getMessage() + ". Phòng khám đã được báo; nếu thấy khó chịu, hãy gọi cấp cứu 115.",
                        "/my-health");
                told++;
            }
        }
        actions.save(new AlertAction(alert, AlertAction.NOTIFIED, OffsetDateTime.now(clock))
                .note(told == 0 ? "Chưa có nhân viên phụ trách để báo" : "Đã báo " + told + " tài khoản"));
        nudge(alert.getClinicId(), alert.getSeverity());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(AlertEvents.Changed changed) {
        nudge(changed.clinicId(), null);
    }

    private void nudge(java.util.UUID clinicId, String severity) {
        if (clinicId == null) {
            return;
        }
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("clinicId", clinicId.toString());
        if (severity != null) {
            body.put("severity", severity);
        }
        messaging.convertAndSend(TOPIC + clinicId, (Object) body);
    }

    static Long accountOf(Customer customer) {
        if (customer.getUser() != null) {
            return customer.getUser().getId();
        }
        Customer guardian = customer.getGuardian();
        return guardian != null && guardian.getUser() != null ? guardian.getUser().getId() : null;
    }
}
