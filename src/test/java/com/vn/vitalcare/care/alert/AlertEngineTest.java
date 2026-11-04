package com.vn.vitalcare.care.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.care.iomt.VitalSignsRepository;
import com.vn.vitalcare.entity.Alert;
import com.vn.vitalcare.entity.AlertAction;
import com.vn.vitalcare.entity.AlertRule;
import com.vn.vitalcare.entity.Customer;
import com.vn.vitalcare.entity.MedicalDevice;
import com.vn.vitalcare.entity.VitalSigns;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

/** BF-05 Alert Rule Evaluation: which rule speaks, and what an alert already open does with a reading. */
class AlertEngineTest {

    private static final UUID CLINIC = UUID.fromString("5b1f8c2e-6a3d-4f7e-9c41-2d8e0a7b3c19");
    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 11, 2, 9, 0, 0, 0, ZoneOffset.ofHours(7));

    private AlertRuleRepository rules;
    private AlertRepository alerts;
    private AlertActionRepository actions;
    private ApplicationEventPublisher events;
    private AlertEngine engine;
    private Customer patient;
    private MedicalDevice device;
    private final List<Alert> open = new ArrayList<>();
    private final List<AlertAction> written = new ArrayList<>();

    @BeforeEach
    void setUp() {
        rules = mock(AlertRuleRepository.class);
        alerts = mock(AlertRepository.class);
        actions = mock(AlertActionRepository.class);
        events = mock(ApplicationEventPublisher.class);
        engine = new AlertEngine(mock(VitalSignsRepository.class), rules, alerts, actions, events,
                Clock.fixed(Instant.from(NOW), ZoneOffset.UTC));
        patient = new Customer();
        ReflectionTestUtils.setField(patient, "id", 37L);
        device = new MedicalDevice();
        ReflectionTestUtils.setField(device, "clinicId", CLINIC);
        when(alerts.findActive(anyLong(), anyString())).thenAnswer(call ->
                open.stream().filter(a -> a.getMetric().equals(call.getArgument(1)) && a.isActive()).toList());
        when(alerts.persist(any(Alert.class))).thenAnswer(call -> {
            Alert a = call.getArgument(0);
            ReflectionTestUtils.setField(a, "id", (long) open.size() + 1);
            open.add(a);
            return a;
        });
        when(actions.save(any(AlertAction.class))).thenAnswer(call -> {
            written.add(call.getArgument(0));
            return call.getArgument(0);
        });
    }

    private static AlertRule rule(String metric, String op, String threshold, String severity, Customer own) {
        AlertRule r = new AlertRule();
        r.setClinicId(CLINIC);
        r.setName(metric + " " + op + " " + threshold);
        r.setMetric(metric);
        r.setOperator(op);
        r.setThreshold(new BigDecimal(threshold));
        r.setSeverity(severity);
        r.setCustomer(own);
        return r;
    }

    private VitalSigns reading(String spo2, Integer heartRate) {
        VitalSigns v = new VitalSigns();
        v.setCustomer(patient);
        v.setDevice(device);
        v.setSpo2(spo2 == null ? null : new BigDecimal(spo2));
        v.setHeartRate(heartRate);
        v.setMeasuredAt(NOW);
        return v;
    }

    private void clinicRules() {
        when(rules.findApplicable(37L, CLINIC)).thenReturn(List.of(
                rule("SPO2", "BELOW", "94", "WARNING", null),
                rule("SPO2", "BELOW", "90", "CRITICAL", null),
                rule("HEART_RATE", "ABOVE", "120", "WARNING", null)));
    }

    @Test
    @DisplayName("A normal reading raises nothing")
    void normal() {
        clinicRules();
        engine.evaluate(reading("97", 80));
        assertThat(open).isEmpty();
        verify(events, never()).publishEvent(any(AlertEvents.Raised.class));
    }

    @Test
    @DisplayName("Of the rules broken, the most severe speaks; the message says the value and the rule")
    void mostSevere() {
        clinicRules();
        engine.evaluate(reading("88", 80));

        assertThat(open).singleElement().satisfies(a -> {
            assertThat(a.getSeverity()).isEqualTo(Alert.CRITICAL);
            assertThat(a.getMeasuredValue()).isEqualByComparingTo("88");
            assertThat(a.getMessage()).contains("SpO₂ 88%").contains("dưới ngưỡng 90%");
            assertThat(a.getClinicId()).isEqualTo(CLINIC);
        });
        assertThat(written).extracting(AlertAction::getAction).containsExactly(AlertAction.TRIGGERED);
        ArgumentCaptor<AlertEvents.Raised> raised = ArgumentCaptor.forClass(AlertEvents.Raised.class);
        verify(events).publishEvent(raised.capture());
        assertThat(raised.getValue().fresh()).isTrue();
    }

    @Test
    @DisplayName("Two measures broken at once raise one alert each")
    void oneAlertPerMeasure() {
        clinicRules();
        engine.evaluate(reading("92", 130));
        assertThat(open).extracting(Alert::getMetric).containsExactlyInAnyOrder("SPO2", "HEART_RATE");
    }

    @Test
    @DisplayName("While an alert is open, another abnormal reading adds to it; a worse one raises its severity")
    void repeatsAndWorsens() {
        clinicRules();
        engine.evaluate(reading("93", null));
        engine.evaluate(reading("92", null));
        assertThat(open).singleElement().satisfies(a -> {
            assertThat(a.getOccurrences()).isEqualTo(2);
            assertThat(a.getSeverity()).isEqualTo(Alert.WARNING);
        });
        engine.evaluate(reading("87", null));

        Alert alert = open.getFirst();
        assertThat(alert.getOccurrences()).isEqualTo(3);
        assertThat(alert.getSeverity()).isEqualTo(Alert.CRITICAL);
        assertThat(alert.getLastValue()).isEqualByComparingTo("87");
        assertThat(written).extracting(AlertAction::getAction)
                .containsExactly(AlertAction.TRIGGERED, AlertAction.REPEATED, AlertAction.SEVERITY_RAISED);
        // Fresh once, worse once: the repeat told nobody.
        verify(events, org.mockito.Mockito.times(2)).publishEvent(any(AlertEvents.Raised.class));
    }

    @Test
    @DisplayName("Once it is closed, the next abnormal reading raises a new alert")
    void closedThenNew() {
        clinicRules();
        engine.evaluate(reading("92", null));
        open.getFirst().resolve(1L, "Đã gọi điện hỏi thăm", NOW);
        engine.evaluate(reading("92", null));
        assertThat(open).hasSize(2);
    }

    @Test
    @DisplayName("BR-21: a patient's own rule for a measure and direction replaces the clinic's")
    void patientRuleWins() {
        // This patient lives with a low SpO₂: 88 is their usual; their doctor set 85.
        when(rules.findApplicable(37L, CLINIC)).thenReturn(List.of(
                rule("SPO2", "BELOW", "94", "WARNING", null),
                rule("SPO2", "BELOW", "90", "CRITICAL", null),
                rule("SPO2", "BELOW", "85", "CRITICAL", patient),
                rule("HEART_RATE", "ABOVE", "120", "WARNING", null)));

        engine.evaluate(reading("88", 125));

        // SpO₂ 88 breaks only the clinic's rules, which no longer apply to her; the heart rate rule still does.
        assertThat(open).extracting(Alert::getMetric).containsExactly("HEART_RATE");
        engine.evaluate(reading("84", null));
        assertThat(open).extracting(Alert::getMetric).containsExactly("HEART_RATE", "SPO2");
    }

    @Test
    @DisplayName("An invalid reading, or one with nobody, raises nothing")
    void invalidIgnored() {
        clinicRules();
        VitalSigns bad = reading("40", 300);
        bad.markInvalid("sensor");
        engine.evaluate(bad);
        VitalSigns orphan = reading("80", null);
        orphan.setCustomer(null);
        engine.evaluate(orphan);
        assertThat(open).isEmpty();
    }
}
