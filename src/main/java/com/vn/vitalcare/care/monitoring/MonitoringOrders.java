package com.vn.vitalcare.care.monitoring;

import com.vn.vitalcare.care.customer.service.CustomerService;
import com.vn.vitalcare.care.encounter.repository.ClinicalEncounterRepository;
import com.vn.vitalcare.care.staff.service.ClinicScope;
import com.vn.vitalcare.entity.ClinicalEncounter;
import com.vn.vitalcare.entity.Customer;
import com.vn.vitalcare.entity.MonitoringOrder;
import com.vn.vitalcare.identity.rowlevel.service.RowLevelSecurity;
import com.vn.vitalcare.share.audit.AuditEvent;
import com.vn.vitalcare.share.data.BaseEntitySpecifications;
import com.vn.vitalcare.share.exception.ConflictException;
import com.vn.vitalcare.share.exception.FieldValidationException;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import com.vn.vitalcare.share.security.CurrentUser;
import com.vn.vitalcare.share.security.rowlevel.Action;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * IoMT monitoring orders (BF-04): the doctor proposes monitoring, explains it,
 * and records the patient's answer. Accepted, the order is active — the only
 * state under which the desk may hand the patient a device — until a doctor
 * ends it; declined, it stays on record as the patient's no.
 */
@Service
@Transactional(readOnly = true)
public class MonitoringOrders {

    public record OrderRequest(
            @NotNull(message = "Choose a patient") Long customerId,
            Long encounterId,
            @NotBlank(message = "Say why monitoring is advised") @Size(max = 1000) String reason,
            @NotBlank(message = "Record the patient's decision") String decision,
            @Size(max = 1000) String consentNote) {
    }

    private final MonitoringOrderRepository orders;
    private final ClinicalEncounterRepository encounters;
    private final CustomerService customers;
    private final ClinicScope clinicScope;
    private final RowLevelSecurity rowLevel;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public MonitoringOrders(MonitoringOrderRepository orders, ClinicalEncounterRepository encounters,
                            CustomerService customers, ClinicScope clinicScope, RowLevelSecurity rowLevel,
                            ApplicationEventPublisher events, Clock clock) {
        this.orders = orders;
        this.encounters = encounters;
        this.customers = customers;
        this.clinicScope = clinicScope;
        this.rowLevel = rowLevel;
        this.events = events;
        this.clock = clock;
    }

    /** A patient's orders, newest first. */
    public List<MonitoringOrder> forPatient(Long customerId) {
        customers.get(customerId);
        return orders.findAll(Specification.allOf(rowLevel.scope(MonitoringOrder.class, Action.READ),
                BaseEntitySpecifications.notDeleted(),
                (r, q, cb) -> {
                    q.orderBy(cb.desc(r.get("decidedAt")));
                    return cb.equal(r.get("customer").get("id"), customerId);
                }));
    }

    @Transactional
    public MonitoringOrder create(OrderRequest request) {
        Customer patient = customers.get(request.customerId());
        String decision = request.decision().trim().toUpperCase(Locale.ROOT);
        if (!MonitoringOrder.ACCEPTED.equals(decision) && !MonitoringOrder.DECLINED.equals(decision)) {
            throw new FieldValidationException("decision", "The patient accepts or declines");
        }
        if (MonitoringOrder.ACCEPTED.equals(decision) && orders.hasActive(patient.getId())) {
            throw new ConflictException("This patient is already under an accepted monitoring order");
        }
        UUID clinic = null;
        ClinicalEncounter encounter = null;
        if (request.encounterId() != null) {
            encounter = encounters.findOne(Specification.allOf(
                            rowLevel.byId(ClinicalEncounter.class, Action.READ, request.encounterId()),
                            BaseEntitySpecifications.notDeleted()))
                    .filter(e -> e.getCustomer() != null && e.getCustomer().getId().equals(patient.getId()))
                    .orElseThrow(() -> new FieldValidationException("encounterId", "That visit is not this patient's"));
            clinic = encounter.getClinicId();
        }
        if (clinic == null) {
            clinic = clinicScope.currentClinicId()
                    .orElseThrow(() -> new FieldValidationException("encounterId", "Order it from a visit"));
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        MonitoringOrder order = new MonitoringOrder();
        order.setCustomer(patient);
        order.setEncounter(encounter);
        order.setClinicId(clinic);
        order.setOrderedById(CurrentUser.id().orElse(null));
        order.setOrderedByName(CurrentUser.displayName().orElse(null));
        order.setReason(request.reason().trim());
        order.setDecision(decision);
        order.setConsentNote(blank(request.consentNote()));
        order.setDecidedAt(now);
        order.setStatus(MonitoringOrder.ACCEPTED.equals(decision) ? MonitoringOrder.ACTIVE : MonitoringOrder.DECLINED);
        MonitoringOrder saved = orders.persist(order);
        orders.flush();
        rowLevel.checkWritable(saved);
        events.publishEvent(new AuditEvent("monitoring_orders", saved.getId(), "consent", null,
                Map.of("customerId", patient.getId(), "decision", decision)));
        return saved;
    }

    /** Monitoring is over: no new device for the patient until a new order is accepted. */
    @Transactional
    public MonitoringOrder end(Long id, String note) {
        MonitoringOrder order = orders.findOne(Specification.allOf(rowLevel.byId(MonitoringOrder.class, Action.WRITE, id),
                        BaseEntitySpecifications.notDeleted()))
                .orElseThrow(() -> new ResourceNotFoundException("Monitoring order", id));
        if (!order.isActive()) {
            throw new ConflictException("Only active monitoring can be ended");
        }
        order.setStatus(MonitoringOrder.ENDED);
        order.setEndedAt(OffsetDateTime.now(clock));
        order.setEndedById(CurrentUser.id().orElse(null));
        order.setEndNote(blank(note));
        events.publishEvent(new AuditEvent("monitoring_orders", id, "end", null, Map.of("status", "ENDED")));
        return orders.persist(order);
    }

    private static String blank(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
