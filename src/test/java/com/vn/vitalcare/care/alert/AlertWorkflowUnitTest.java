package com.vn.vitalcare.care.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.care.customer.service.CustomerService;
import com.vn.vitalcare.care.staff.service.ClinicScope;
import com.vn.vitalcare.entity.Alert;
import com.vn.vitalcare.entity.AlertRule;
import com.vn.vitalcare.identity.rowlevel.service.RowLevelSecurity;
import com.vn.vitalcare.share.exception.ConflictException;
import com.vn.vitalcare.share.exception.FieldValidationException;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.jpa.domain.Specification;

/** FE-19 Alert Rule & Response Workflow: AlertRules, Alerts. Report-5.1, sheet BE_Alerts. */
class AlertWorkflowUnitTest {

    private static final UUID CLINIC = UUID.fromString("5b1f8c2e-6a3d-4f7e-9c41-2d8e0a7b3c19");
    private AlertRuleRepository ruleRepo;
    private AlertRepository alertRepo;
    private AlertActionRepository actions;
    private ClinicScope scope;
    private RowLevelSecurity rowLevel;
    private AlertRules rules;
    private Alerts alerts;

    @BeforeEach
    void setUp() {
        ruleRepo = mock(AlertRuleRepository.class);
        alertRepo = mock(AlertRepository.class);
        actions = mock(AlertActionRepository.class);
        scope = mock(ClinicScope.class);
        rowLevel = mock(RowLevelSecurity.class);
        when(rowLevel.byId(any(), any(), any())).thenReturn((root, query, cb) -> null);
        when(scope.currentClinicId()).thenReturn(Optional.of(CLINIC));
        when(ruleRepo.persist(any(AlertRule.class))).thenAnswer(i -> i.getArgument(0));
        when(alertRepo.persist(any(Alert.class))).thenAnswer(i -> i.getArgument(0));
        when(alertRepo.customerOf(any())).thenReturn(Optional.empty());
        Clock clock = Clock.fixed(Instant.parse("2026-11-22T03:00:00Z"), ZoneOffset.UTC);
        CustomerService customers = mock(CustomerService.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        rules = new AlertRules(ruleRepo, customers, scope, rowLevel, events, clock);
        alerts = new Alerts(alertRepo, actions, customers, rowLevel, events, scope, clock);
    }

    private static AlertDtos.RuleRequest rule(String metric, String op, String threshold, String severity) {
        return new AlertDtos.RuleRequest("  SpO₂ thấp  ", metric, op, new BigDecimal(threshold), severity, true,
                "  Báo bác sĩ trực  ", null, null);
    }

    private Alert alert(String status) {
        Alert a = new Alert();
        if (!Alert.OPEN.equals(status)) {
            org.springframework.test.util.ReflectionTestUtils.setField(a, "status", status);
        }
        when(alertRepo.findOne(any(Specification.class))).thenReturn(Optional.of(a));
        return a;
    }

    @Test void ar01_ruleCreatedNormalisedAtMyClinic() {
        AlertRule r = rules.create(rule(" spo2 ", "below", "92", "critical"));
        assertEquals("SPO2", r.getMetric());
        assertEquals("BELOW", r.getOperator());
        assertEquals("CRITICAL", r.getSeverity());
        assertEquals("SpO₂ thấp", r.getName());
        assertEquals("Báo bác sĩ trực", r.getNote());
        assertEquals(CLINIC, r.getClinicId());
        assertTrue(r.isEnabled());
        verify(rowLevel).checkWritable(r);
    }

    @Test void ar02_unknownMetricOperatorSeverityRefused() {
        assertEquals("Choose heart rate, SpO2, skin or body temperature",
                assertThrows(FieldValidationException.class, () -> rules.create(rule("BLOOD_PRESSURE", "BELOW", "90", "WARNING"))).getMessage());
        assertEquals("Choose below or above",
                assertThrows(FieldValidationException.class, () -> rules.create(rule("SPO2", "EQUAL", "90", "WARNING"))).getMessage());
        assertEquals("Choose warning or critical",
                assertThrows(FieldValidationException.class, () -> rules.create(rule("SPO2", "BELOW", "90", "INFO"))).getMessage());
        verify(ruleRepo, never()).persist(any());
    }

    @Test void ar03_thresholdWithinWhatTheBandMeasures() {
        rules.create(rule("SPO2", "BELOW", "50", "WARNING"));
        rules.create(rule("SPO2", "BELOW", "100", "WARNING"));
        assertEquals("Between 50 and 100 for this measure",
                assertThrows(FieldValidationException.class, () -> rules.create(rule("SPO2", "BELOW", "49", "WARNING"))).getMessage());
        assertEquals("Between 20 and 250 for this measure",
                assertThrows(FieldValidationException.class, () -> rules.create(rule("HEART_RATE", "ABOVE", "251", "WARNING"))).getMessage());
        assertEquals("Between 34 and 43 for this measure",
                assertThrows(FieldValidationException.class, () -> rules.create(rule("BODY_TEMPERATURE", "ABOVE", "43.1", "WARNING"))).getMessage());
    }

    @Test void ar04_noClinicChosen() {
        when(scope.currentClinicId()).thenReturn(Optional.empty());
        assertEquals("Choose a clinic",
                assertThrows(FieldValidationException.class, () -> rules.create(rule("SPO2", "BELOW", "92", "WARNING"))).getMessage());
    }

    @Test void ar05_updateDisables_deleteIsSoft_unknownIs404() {
        AlertRule existing = new AlertRule();
        existing.setClinicId(CLINIC);
        when(ruleRepo.findOne(any(Specification.class))).thenReturn(Optional.of(existing));
        rules.update(1L, new AlertDtos.RuleRequest("Sốt", "BODY_TEMPERATURE", "ABOVE", new BigDecimal("38"), "WARNING",
                false, "Theo dõi", null, null));
        assertEquals(false, existing.isEnabled());
        assertEquals(CLINIC, existing.getClinicId());
        rules.delete(1L);
        assertNotNull(existing.getDeletedAt());
        when(ruleRepo.findOne(any(Specification.class))).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> rules.get(9L));
    }

    @Test void al01_acknowledgeOnlyAnOpenAlert() {
        Alert a = alert(Alert.OPEN);
        alerts.acknowledge(1L);
        assertEquals(Alert.ACKNOWLEDGED, a.getStatus());
        assertNotNull(a.getAcknowledgedAt());
        assertEquals("Only an open alert can be acknowledged",
                assertThrows(ConflictException.class, () -> alerts.acknowledge(1L)).getMessage());
    }

    @Test void al02_resolveNeedsNotes_acknowledgesOnTheWay() {
        Alert a = alert(Alert.OPEN);
        assertEquals("Handling notes are required to resolve an alert",
                assertThrows(FieldValidationException.class, () -> alerts.resolve(1L, "  ")).getMessage());
        alerts.resolve(1L, "  Đã gọi bệnh nhân  ");
        assertEquals(Alert.RESOLVED, a.getStatus());
        assertEquals("Đã gọi bệnh nhân", a.getHandlingNotes());
        assertNotNull(a.getAcknowledgedAt());
    }

    @Test void al03_dismissNeedsAReason_closedAlertStaysClosed() {
        Alert a = alert(Alert.ACKNOWLEDGED);
        assertEquals("A reason is required to dismiss an alert",
                assertThrows(FieldValidationException.class, () -> alerts.dismiss(1L, null)).getMessage());
        alerts.dismiss(1L, "Đeo lỏng vòng");
        assertEquals(Alert.DISMISSED, a.getStatus());
        assertEquals("Đeo lỏng vòng", a.getDismissReason());
        assertEquals("This alert has already been closed",
                assertThrows(ConflictException.class, () -> alerts.resolve(1L, "x")).getMessage());
        assertEquals("This alert has already been closed",
                assertThrows(ConflictException.class, () -> alerts.dismiss(1L, "x")).getMessage());
    }

    @Test void al04_noteRequired_longNoteClippedTo4000() {
        alert(Alert.OPEN);
        assertEquals("Write the note", assertThrows(FieldValidationException.class, () -> alerts.note(1L, " ")).getMessage());
        alerts.note(1L, "n".repeat(4500));
        verify(actions).save(any());
    }

    @Test void al05_alertOutsideMyScopeIs404() {
        when(alertRepo.findOne(any(Specification.class))).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> alerts.acknowledge(9L));
        assertThrows(ResourceNotFoundException.class, () -> alerts.get(9L));
    }
}
