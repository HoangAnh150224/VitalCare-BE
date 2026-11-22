package com.vn.vitalcare.care.alert;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.care.assignment.repository.MonitoringAssignmentRepository;
import com.vn.vitalcare.care.notification.Notifier;
import com.vn.vitalcare.entity.Alert;
import com.vn.vitalcare.entity.Customer;
import com.vn.vitalcare.entity.Employee;
import com.vn.vitalcare.entity.MonitoringAssignment;
import com.vn.vitalcare.entity.NotificationLevel;
import com.vn.vitalcare.identity.user.entity.User;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/** FE-13 / FE-19: who is told when an alert is raised. Report-5.1, sheet BE_Gaps. */
class AlertNotificationsUnitTest {

    private static final UUID CLINIC = UUID.fromString("5b1f8c2e-6a3d-4f7e-9c41-2d8e0a7b3c19");
    private AlertRepository alerts;
    private AlertActionRepository actions;
    private MonitoringAssignmentRepository careTeams;
    private Notifier notifier;
    private SimpMessagingTemplate messaging;
    private AlertNotifications notices;
    private Alert alert;
    private Customer patient;

    private static User account(long id) {
        User u = mock(User.class);
        when(u.getId()).thenReturn(id);
        return u;
    }

    private static MonitoringAssignment member(long userId) {
        Employee e = new Employee();
        e.setUser(account(userId));
        MonitoringAssignment a = new MonitoringAssignment();
        a.setEmployee(e);
        return a;
    }

    @BeforeEach
    void setUp() {
        alerts = mock(AlertRepository.class);
        actions = mock(AlertActionRepository.class);
        careTeams = mock(MonitoringAssignmentRepository.class);
        notifier = mock(Notifier.class);
        messaging = mock(SimpMessagingTemplate.class);
        notices = new AlertNotifications(alerts, actions, careTeams, notifier, messaging,
                Clock.fixed(Instant.parse("2026-11-22T03:00:00Z"), ZoneOffset.UTC));
        patient = new Customer();
        ReflectionTestUtils.setField(patient, "id", 1L);
        patient.setFullName("Trần Thị Bích");
        alert = new Alert();
        ReflectionTestUtils.setField(alert, "id", 7L);
        ReflectionTestUtils.setField(alert, "customer", patient);
        ReflectionTestUtils.setField(alert, "clinicId", CLINIC);
        ReflectionTestUtils.setField(alert, "message", "SpO₂ 88% dưới 90%");
        when(alerts.findForNotice(7L)).thenReturn(Optional.of(alert));
    }

    private AlertEvents.Raised raised(String severity) {
        ReflectionTestUtils.setField(alert, "severity", severity);
        return new AlertEvents.Raised(7L, CLINIC, 1L, severity, true);
    }

    @Test void an01_warningTellsTheCareTeamOnce_notThePatient() {
        List<MonitoringAssignment> team = List.of(member(11L), member(12L), member(11L));
        when(careTeams.findByCustomerIdAndUnassignedAtIsNullAndDeletedAtIsNull(1L)).thenReturn(team);
        User own = account(42L);
        patient.setUser(own);
        notices.on(raised(Alert.WARNING));
        verify(notifier).notify(11L, NotificationLevel.WARNING, "alert", "Cảnh báo: Trần Thị Bích", "SpO₂ 88% dưới 90%", "/alerts/show/7");
        verify(notifier).notify(eq(12L), any(), anyString(), anyString(), anyString(), anyString());
        verify(notifier, never()).notify(eq(42L), any(), anyString(), anyString(), anyString(), anyString());
        verify(actions).save(any());
    }

    @Test void an02_criticalAlsoTellsThePatient_withTheEmergencyNumber() {
        List<MonitoringAssignment> team = List.of(member(11L));
        when(careTeams.findByCustomerIdAndUnassignedAtIsNullAndDeletedAtIsNull(1L)).thenReturn(team);
        User own = account(42L);
        patient.setUser(own);
        notices.on(raised(Alert.CRITICAL));
        verify(notifier).notify(eq(11L), eq(NotificationLevel.ERROR), eq("alert"), eq("Cảnh báo khẩn: Trần Thị Bích"), anyString(), anyString());
        verify(notifier).notify(42L, NotificationLevel.ERROR, "alert", "Chỉ số sức khỏe bất thường",
                "SpO₂ 88% dưới 90%. Phòng khám đã được báo; nếu thấy khó chịu, hãy gọi cấp cứu 115.", "/my-health");
    }

    @Test void an03_clinicBoardIsNudgedWithTheSeverity() {
        when(careTeams.findByCustomerIdAndUnassignedAtIsNullAndDeletedAtIsNull(1L)).thenReturn(List.of());
        notices.on(raised(Alert.WARNING));
        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(messaging).convertAndSend(eq("/topic/alerts/" + CLINIC), body.capture());
        org.junit.jupiter.api.Assertions.assertEquals(Map.of("clinicId", CLINIC.toString(), "severity", "WARNING"), body.getValue());
    }

    @Test void an04_alertGoneBeforeTheNotice_nothingHappens() {
        when(alerts.findForNotice(8L)).thenReturn(Optional.empty());
        notices.on(new AlertEvents.Raised(8L, CLINIC, 1L, Alert.CRITICAL, true));
        verifyNoInteractions(notifier, messaging, actions);
    }

    @Test void an05_changedAlertNudgesTheBoard_noClinicNoNudge() {
        notices.on(new AlertEvents.Changed(7L, CLINIC));
        verify(messaging).convertAndSend(eq("/topic/alerts/" + CLINIC), any(Object.class));
        SimpMessagingTemplate quiet = mock(SimpMessagingTemplate.class);
        new AlertNotifications(alerts, actions, careTeams, notifier, quiet, Clock.systemUTC())
                .on(new AlertEvents.Changed(7L, null));
        verifyNoInteractions(quiet);
    }
}
