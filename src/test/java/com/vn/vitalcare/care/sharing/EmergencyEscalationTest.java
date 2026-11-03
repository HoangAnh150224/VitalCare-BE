package com.vn.vitalcare.care.sharing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.care.alert.AlertActionRepository;
import com.vn.vitalcare.care.alert.AlertEvents;
import com.vn.vitalcare.care.alert.AlertRepository;
import com.vn.vitalcare.care.assignment.repository.MonitoringAssignmentRepository;
import com.vn.vitalcare.care.notification.Notifier;
import com.vn.vitalcare.entity.Alert;
import com.vn.vitalcare.entity.AlertAction;
import com.vn.vitalcare.entity.Customer;
import com.vn.vitalcare.entity.EmergencyNotice;
import com.vn.vitalcare.entity.EscalationCall;
import com.vn.vitalcare.entity.SharingPermission;
import com.vn.vitalcare.identity.user.entity.User;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** BF-06 Phone-call Escalation: who is told, and what happens when nobody answers in 30 s. */
class EmergencyEscalationTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 11, 3, 9, 0, 0, 0, ZoneOffset.ofHours(7));

    private SharingPermissionRepository permissions;
    private EmergencyNoticeRepository notices;
    private EscalationCallRepository calls;
    private AlertRepository alerts;
    private AlertActionRepository actions;
    private EmergencyChannels channels;
    private Notifier notifier;
    private EmergencyEscalation escalation;
    private Alert alert;
    private Customer patient;
    private User caregiver;
    private final List<EmergencyNotice> saved = new ArrayList<>();
    private final List<EscalationCall> logged = new ArrayList<>();

    @BeforeEach
    void setUp() {
        permissions = mock(SharingPermissionRepository.class);
        notices = mock(EmergencyNoticeRepository.class);
        calls = mock(EscalationCallRepository.class);
        alerts = mock(AlertRepository.class);
        actions = mock(AlertActionRepository.class);
        channels = mock(EmergencyChannels.class);
        notifier = mock(Notifier.class);
        escalation = new EmergencyEscalation(permissions, notices, calls, alerts, actions,
                mock(MonitoringAssignmentRepository.class), channels, notifier,
                Clock.fixed(Instant.from(NOW), ZoneOffset.UTC), Duration.ofSeconds(30));

        patient = new Customer();
        ReflectionTestUtils.setField(patient, "id", 37L);
        patient.setFullName("Trần Thu Trang");
        alert = new Alert();
        ReflectionTestUtils.setField(alert, "id", 5L);
        ReflectionTestUtils.setField(alert, "customer", patient);
        ReflectionTestUtils.setField(alert, "severity", Alert.CRITICAL);
        ReflectionTestUtils.setField(alert, "message", "SpO₂ 86% dưới ngưỡng 90%");
        caregiver = mock(User.class);
        when(caregiver.getId()).thenReturn(101L);
        when(caregiver.getFullName()).thenReturn("Nguyễn Văn Bình");
        when(alerts.findForNotice(5L)).thenReturn(Optional.of(alert));
        when(notices.save(any(EmergencyNotice.class))).thenAnswer(c -> {
            EmergencyNotice n = c.getArgument(0);
            if (n.getId() == null) {
                ReflectionTestUtils.setField(n, "id", (long) saved.size() + 1);
                saved.add(n);
            }
            return n;
        });
        when(calls.save(any(EscalationCall.class))).thenAnswer(c -> {
            logged.add(c.getArgument(0));
            return c.getArgument(0);
        });
        when(channels.call(anyString(), anyString())).thenReturn(new EmergencyChannels.Outcome("SIMULATED", "ok"));
        when(channels.sms(anyString(), anyString())).thenReturn(new EmergencyChannels.Outcome("SENT", "ok"));
    }

    private SharingPermission grant(boolean alertsShared) {
        SharingPermission p = new SharingPermission();
        p.setCustomer(patient);
        p.setCaregiver(caregiver);
        p.setShareAlerts(alertsShared);
        p.setEmergencyPhone("+84912345678");
        p.setStatus(SharingPermission.ACTIVE);
        return p;
    }

    @Test
    @DisplayName("A critical alert is told to the caregivers sharing alerts, as an emergency notification")
    void toldToCaregivers() {
        when(permissions.findInForceOf(any(), any())).thenReturn(List.of(grant(true)));

        escalation.on(new AlertEvents.Raised(5L, null, 37L, Alert.CRITICAL, true));

        assertThat(saved).singleElement().satisfies(n -> {
            assertThat(n.getStatus()).isEqualTo(EmergencyNotice.PENDING);
            assertThat(n.getEmergencyPhone()).isEqualTo("+84912345678");
        });
        verify(notifier).notify(any(), any(), org.mockito.ArgumentMatchers.eq("emergency"), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("A warning, or a caregiver not sharing alerts, gets no emergency")
    void notForWarningsOrWithoutAlerts() {
        when(permissions.findInForceOf(any(), any())).thenReturn(List.of(grant(false)));
        escalation.on(new AlertEvents.Raised(5L, null, 37L, Alert.CRITICAL, true));
        escalation.on(new AlertEvents.Raised(5L, null, 37L, Alert.WARNING, true));
        assertThat(saved).isEmpty();
    }

    @Test
    @DisplayName("Unacknowledged past the delay: a call and an SMS to the emergency number, both in the log")
    void escalatesWithCallAndSms() {
        EmergencyNotice n = new EmergencyNotice();
        n.setAlert(alert);
        n.setCustomer(patient);
        n.setRecipient(caregiver);
        n.setEmergencyPhone("+84912345678");
        n.setNotifiedAt(NOW.minusSeconds(31));
        n.setStatus(EmergencyNotice.PENDING);
        when(notices.findDue(any(), any())).thenReturn(List.of(n));

        escalation.sweep();

        assertThat(n.getStatus()).isEqualTo(EmergencyNotice.ESCALATED);
        assertThat(logged).extracting(EscalationCall::getChannel).containsExactly(EscalationCall.CALL, EscalationCall.SMS);
        assertThat(logged).allSatisfy(c -> assertThat(c.getPhone()).isEqualTo("+84912345678"));
        verify(actions).save(org.mockito.ArgumentMatchers.argThat((AlertAction a) -> AlertAction.ESCALATED.equals(a.getAction())));
    }

    @Test
    @DisplayName("Nothing due: nobody is called")
    void nothingDue() {
        when(notices.findDue(any(), any())).thenReturn(List.of());
        escalation.sweep();
        verify(channels, never()).call(anyString(), anyString());
    }

    @Test
    @DisplayName("The SMS text goes out without accents")
    void plainText() {
        assertThat(EmergencyEscalation.plain("Trần Thu Trang: SpO₂ 86% dưới ngưỡng")).isEqualTo("Tran Thu Trang: SpO 86% duoi nguong");
    }
}
