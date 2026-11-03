package com.vn.vitalcare.care.alert;

import com.vn.vitalcare.entity.Alert;
import com.vn.vitalcare.entity.AlertAction;
import com.vn.vitalcare.entity.AlertRule;
import com.vn.vitalcare.entity.Customer;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** The alert screens' shapes (FE-05). */
public final class AlertDtos {

    private AlertDtos() {
    }

    public record Patient(Long id, String customerCode, String fullName) {
        static Patient of(Customer c) {
            return c == null ? null : new Patient(c.getId(), c.getCustomerCode(), c.getFullName());
        }
    }

    public record RuleResponse(Long id, UUID clinicId, Patient patient, String name, String metric, String operator,
                               BigDecimal threshold, String severity, boolean enabled, String note,
                               OffsetDateTime updatedAt) {
        static RuleResponse of(AlertRule r) {
            return new RuleResponse(r.getId(), r.getClinicId(), Patient.of(r.getCustomer()), r.getName(),
                    r.getMetric(), r.getOperator(), r.getThreshold(), r.getSeverity(), r.isEnabled(), r.getNote(),
                    r.getUpdatedAt() == null ? null : r.getUpdatedAt().atOffset(java.time.ZoneOffset.UTC));
        }
    }

    /**
     * A rule as written: {@code customerId} makes it that patient's own; {@code clinicId} is the system
     * administrator's to choose — anybody else writes their own clinic's.
     */
    public record RuleRequest(
            @NotBlank(message = "Name is required") @Size(max = 255) String name,
            @NotBlank(message = "Choose a measure") String metric,
            @NotBlank(message = "Choose below or above") String operator,
            @NotNull(message = "Threshold is required") BigDecimal threshold,
            @NotBlank(message = "Choose a severity") String severity,
            Boolean enabled,
            @Size(max = 1000) String note,
            Long customerId,
            UUID clinicId) {
    }

    public record AlertResponse(Long id, Patient patient, UUID clinicId, Long deviceId, String deviceCode,
                                String metric, String operator, BigDecimal threshold, BigDecimal measuredValue,
                                BigDecimal lastValue, String severity, String status, String message,
                                int occurrences, OffsetDateTime triggeredAt, OffsetDateTime lastTriggeredAt,
                                OffsetDateTime acknowledgedAt, OffsetDateTime resolvedAt, OffsetDateTime dismissedAt,
                                Long ruleId, String ruleName) {
        static AlertResponse of(Alert a) {
            return new AlertResponse(a.getId(), Patient.of(a.getCustomer()), a.getClinicId(),
                    a.getDevice() == null ? null : a.getDevice().getId(),
                    a.getDevice() == null ? null : a.getDevice().getDeviceCode(), a.getMetric(), a.getOperator(),
                    a.getThreshold(), a.getMeasuredValue(), a.getLastValue(), a.getSeverity(), a.getStatus(),
                    a.getMessage(), a.getOccurrences(), a.getTriggeredAt(), a.getLastTriggeredAt(),
                    a.getAcknowledgedAt(), a.getResolvedAt(), a.getDismissedAt(),
                    a.getRule() == null ? null : a.getRule().getId(),
                    a.getRule() == null ? null : a.getRule().getName());
        }
    }

    public record ActionResponse(Long id, String action, Long vitalSignId, BigDecimal value, String severity,
                                 Long actorId, String actorName, String note, OffsetDateTime occurredAt) {
        static ActionResponse of(AlertAction x) {
            return new ActionResponse(x.getId(), x.getAction(),
                    x.getVitalSign() == null ? null : x.getVitalSign().getId(), x.getValue(), x.getSeverity(),
                    x.getActorId(), x.getActorName(), x.getNote(), x.getOccurredAt());
        }
    }

    public record AlertDetail(AlertResponse alert, String handlingNotes, String dismissReason,
                              List<ActionResponse> history) {
    }

    /** Abnormal Reading List: each reading that raised or fed an alert. */
    public record AbnormalReading(Long actionId, Long vitalSignId, OffsetDateTime measuredAt, String metric,
                                  BigDecimal value, String severity, String action, Long alertId, String alertStatus,
                                  Patient patient) {
        static AbnormalReading of(AlertAction x) {
            Alert a = x.getAlert();
            return new AbnormalReading(x.getId(), x.getVitalSign().getId(), x.getVitalSign().getMeasuredAt(),
                    a.getMetric(), x.getValue(), x.getSeverity(), x.getAction(), a.getId(), a.getStatus(),
                    Patient.of(a.getCustomer()));
        }
    }

    public record Note(@Size(max = 4000) String note) {
    }
}
