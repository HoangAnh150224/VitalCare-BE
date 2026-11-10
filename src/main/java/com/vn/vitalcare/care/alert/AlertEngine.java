package com.vn.vitalcare.care.alert;

import com.vn.vitalcare.care.iomt.VitalEvents;
import com.vn.vitalcare.care.iomt.VitalSignsRepository;
import com.vn.vitalcare.entity.Alert;
import com.vn.vitalcare.entity.AlertAction;
import com.vn.vitalcare.entity.AlertRule;
import com.vn.vitalcare.entity.VitalSigns;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Checks each stored reading against the rules (BF-05 Alert Rule Evaluation).
 *
 * <p>After the reading is committed and in a transaction of its own: a fault
 * here can delay an alert, never lose a reading. Per measure, a patient's own
 * rules for a direction (below / above) replace the clinic's for that direction
 * (BR-21); of the rules broken, the most severe one speaks. An alert already
 * open for that patient and measure takes the reading in instead of a second
 * alert being raised.
 */
@Component
public class AlertEngine {

    private static final Logger log = LoggerFactory.getLogger(AlertEngine.class);

    static final Map<String, String> LABELS = Map.of(
            "HEART_RATE", "Nhịp tim", "SPO2", "SpO₂", "SKIN_TEMPERATURE", "Nhiệt độ da",
            "BODY_TEMPERATURE", "Nhiệt độ cơ thể");
    static final Map<String, String> UNITS = Map.of("HEART_RATE", " bpm", "SPO2", "%", "SKIN_TEMPERATURE", " °C",
            "BODY_TEMPERATURE", " °C");

    private final VitalSignsRepository vitals;
    private final AlertRuleRepository rules;
    private final AlertRepository alerts;
    private final AlertActionRepository actions;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public AlertEngine(VitalSignsRepository vitals, AlertRuleRepository rules, AlertRepository alerts,
                       AlertActionRepository actions, ApplicationEventPublisher events, Clock clock) {
        this.vitals = vitals;
        this.rules = rules;
        this.alerts = alerts;
        this.actions = actions;
        this.events = events;
        this.clock = clock;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(VitalEvents.Recorded recorded) {
        try {
            vitals.findWithDetailsById(recorded.vitalId()).ifPresent(this::evaluate);
        } catch (RuntimeException e) {
            log.error("Alert rules could not be checked for reading {}", recorded.vitalId(), e);
            throw e;
        }
    }

    /** Evaluates one reading; package-visible for tests. */
    void evaluate(VitalSigns reading) {
        if (!reading.isValid() || reading.getCustomer() == null) {
            return;
        }
        UUID clinicId = reading.getDevice() == null ? null : reading.getDevice().getClinicId();
        Long customerId = reading.getCustomer().getId();
        alerts.lockPatient(customerId);
        List<AlertRule> applicable = applicable(rules.findApplicable(customerId, clinicId));
        for (String metric : List.of("HEART_RATE", "SPO2", "SKIN_TEMPERATURE", "BODY_TEMPERATURE")) {
            BigDecimal value = valueOf(reading, metric);
            if (value == null) {
                continue;
            }
            applicable.stream()
                    .filter(r -> metric.equals(r.getMetric()) && r.breachedBy(value))
                    .max(Comparator.comparingInt((AlertRule r) -> Alert.rank(r.getSeverity())))
                    .ifPresent(rule -> raise(reading, rule, value));
        }
    }

    /** BR-21: for a measure and direction, the patient's own rules, if there are any, else the clinic's. */
    static List<AlertRule> applicable(List<AlertRule> all) {
        Set<String> ownKeys = all.stream().filter(AlertRule::isPatientRule)
                .map(r -> r.getMetric() + "/" + r.getOperator()).collect(Collectors.toSet());
        return all.stream()
                .filter(r -> r.isPatientRule() || !ownKeys.contains(r.getMetric() + "/" + r.getOperator()))
                .toList();
    }

    private void raise(VitalSigns reading, AlertRule rule, BigDecimal value) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        String message = message(rule, value);
        Optional<Alert> open = alerts.findActive(reading.getCustomer().getId(), rule.getMetric()).stream().findFirst();
        if (open.isPresent()) {
            Alert alert = open.get();
            boolean worse = alert.repeat(rule, value, message, now);
            actions.save(new AlertAction(alert, worse ? AlertAction.SEVERITY_RAISED : AlertAction.REPEATED, now)
                    .reading(reading, value));
            if (worse) {
                events.publishEvent(new AlertEvents.Raised(alert.getId(), alert.getClinicId(),
                        reading.getCustomer().getId(), alert.getSeverity(), false));
            }
            return;
        }
        Alert alert = new Alert();
        alert.raise(reading, rule, value, message, now);
        Alert saved = alerts.persist(alert);
        actions.save(new AlertAction(saved, AlertAction.TRIGGERED, now).reading(reading, value));
        events.publishEvent(new AlertEvents.Raised(saved.getId(), saved.getClinicId(), reading.getCustomer().getId(),
                saved.getSeverity(), true));
    }

    static String message(AlertRule rule, BigDecimal value) {
        String unit = UNITS.get(rule.getMetric());
        return "%s %s%s %s ngưỡng %s%s (%s)".formatted(LABELS.get(rule.getMetric()), plain(value), unit,
                AlertRule.BELOW.equals(rule.getOperator()) ? "dưới" : "vượt", plain(rule.getThreshold()), unit,
                rule.getName());
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    static BigDecimal valueOf(VitalSigns v, String metric) {
        return switch (metric) {
            case "HEART_RATE" -> v.getHeartRate() == null ? null : BigDecimal.valueOf(v.getHeartRate());
            case "SPO2" -> v.getSpo2();
            case "SKIN_TEMPERATURE" -> v.getTemperature();
            case "BODY_TEMPERATURE" -> v.getBodyTemperature();
            default -> null;
        };
    }

}
