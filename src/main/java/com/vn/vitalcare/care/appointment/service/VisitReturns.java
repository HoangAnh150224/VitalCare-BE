package com.vn.vitalcare.care.appointment.service;

import com.vn.vitalcare.care.appointment.repository.AppointmentRepository;
import com.vn.vitalcare.care.encounter.repository.ClinicalEncounterRepository;
import com.vn.vitalcare.care.examroom.service.ExamRoomEvents;
import com.vn.vitalcare.care.serviceorder.ServiceOrderRepository;
import com.vn.vitalcare.entity.Appointment;
import com.vn.vitalcare.entity.AppointmentStatus;
import com.vn.vitalcare.entity.ClinicalEncounter;
import com.vn.vitalcare.entity.ServiceOrder;
import com.vn.vitalcare.entity.ServiceOrderStatus;
import com.vn.vitalcare.share.data.BaseEntitySpecifications;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import jakarta.persistence.EntityManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

/**
 * A visit out of the room, and its way back: what it went out for (the
 * services ordered at it, the doctors it was referred to), and, once none of
 * that is still to be done, back into its doctor's queue, at the front.
 *
 * <p>Driven by {@link VisitEvents}, heard in the transaction that published
 * them: a result recorded, an order cancelled, a referral's visit over.
 */
@Component
public class VisitReturns {

    /** A referral still to happen: registered, waiting, in the room, or itself away. */
    private static final Set<AppointmentStatus> REFERRAL_OPEN = Set.of(AppointmentStatus.SCHEDULED,
            AppointmentStatus.CHECKED_IN, AppointmentStatus.IN_EXAM, AppointmentStatus.AWAITING_RESULTS);
    /** An ordered service still to be done; a result in (read or not) is enough to come back. */
    private static final Set<ServiceOrderStatus> ORDER_OPEN = Set.of(ServiceOrderStatus.ORDERED,
            ServiceOrderStatus.IN_PROGRESS);

    /**
     * One thing the patient went out for.
     *
     * @param kind   {@code service} or {@code referral}
     * @param name   the service, or the doctor referred to
     * @param status the order's or the referral visit's status, lower-case as on the wire
     * @param done   whether the patient need not wait for it any more
     */
    public record Task(String kind, String name, String status, boolean done) {
    }

    private final AppointmentRepository appointments;
    private final ClinicalEncounterRepository encounters;
    private final ServiceOrderRepository orders;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final EntityManager entityManager;

    public VisitReturns(AppointmentRepository appointments, ClinicalEncounterRepository encounters,
                        ServiceOrderRepository orders, ApplicationEventPublisher events, Clock clock,
                        EntityManager entityManager) {
        this.appointments = appointments;
        this.encounters = encounters;
        this.orders = orders;
        this.events = events;
        this.clock = clock;
        this.entityManager = entityManager;
    }

    @EventListener
    public void onOrdersChanged(VisitEvents.OrdersChanged changed) {
        if (changed.appointmentId() != null) {
            returnIfDone(changed.appointmentId());
        }
    }

    @EventListener
    public void onVisitEnded(VisitEvents.VisitEnded ended) {
        load(ended.appointmentId())
                .map(Appointment::getReferredFrom)
                .ifPresent(origin -> returnIfDone(origin.getId()));
    }

    /** What the visit went out for, services first, then referrals; empty for a visit that never left. */
    public List<Task> tasksOf(Appointment appointment) {
        List<Task> tasks = new ArrayList<>();
        for (ClinicalEncounter encounter : encounters.findOfOwnAppointment(appointment.getId())) {
            for (ServiceOrder order : orders.findByEncounter(encounter.getId())) {
                if (order.getStatus() == ServiceOrderStatus.CANCELLED) {
                    continue;
                }
                tasks.add(new Task("service", order.getService().getName(), order.getStatus().getValue(),
                        !ORDER_OPEN.contains(order.getStatus())));
            }
        }
        for (Appointment referral : appointments.findReferralsOf(appointment.getId())) {
            tasks.add(new Task("referral", referral.getEmployee().getUser().getFullName(),
                    referral.getStatus().getValue(), !REFERRAL_OPEN.contains(referral.getStatus())));
        }
        return tasks;
    }

    /** Whether anything the visit went out for is still to be done. */
    public boolean hasOpenTasks(Appointment appointment) {
        return tasksOf(appointment).stream().anyMatch(task -> !task.done());
    }

    /**
     * Back to its doctor's queue, first in line, if it is away and nothing it
     * went out for is left; the doctor's room is nudged to call it.
     *
     * <p>The visit's row is locked first and re-read: two results recorded at
     * once for the same visit then decide one after the other, and the second
     * sees the first's committed result, so the visit is not left away for
     * good with each thinking the other is still to come.
     */
    public void returnIfDone(Long appointmentId) {
        appointments.lockById(appointmentId)
                .map(locked -> {
                    entityManager.refresh(locked);
                    return locked;
                })
                .filter(appointment -> appointment.getStatus() == AppointmentStatus.AWAITING_RESULTS)
                .filter(appointment -> !hasOpenTasks(appointment))
                .ifPresent(appointment -> {
                    appointment.returnToQueue(OffsetDateTime.now(clock));
                    appointments.persist(appointment);
                    events.publishEvent(new ExamRoomEvents.QueueJoined(
                            appointment.getClinicId(), appointment.getEmployee().getId()));
                });
    }

    private Optional<Appointment> load(Long appointmentId) {
        return appointments.findOne(Specification.allOf(
                BaseEntitySpecifications.notDeleted(),
                (root, query, cb) -> cb.equal(root.get("id"), appointmentId)));
    }
}
