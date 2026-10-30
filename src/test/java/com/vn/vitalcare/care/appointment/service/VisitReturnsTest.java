package com.vn.vitalcare.care.appointment.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.care.appointment.repository.AppointmentRepository;
import com.vn.vitalcare.care.encounter.repository.ClinicalEncounterRepository;
import com.vn.vitalcare.care.examroom.service.ExamRoomEvents;
import com.vn.vitalcare.care.serviceorder.ServiceOrderRepository;
import com.vn.vitalcare.entity.Appointment;
import com.vn.vitalcare.entity.AppointmentStatus;
import com.vn.vitalcare.entity.ClinicalEncounter;
import com.vn.vitalcare.entity.Employee;
import com.vn.vitalcare.entity.MedicalService;
import com.vn.vitalcare.entity.ServiceOrder;
import com.vn.vitalcare.entity.ServiceOrderStatus;
import com.vn.vitalcare.identity.user.entity.User;
import com.vn.vitalcare.identity.user.entity.UserStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * A visit away from the room comes back to its doctor's queue, first in line,
 * once everything it went out for is done, and not before.
 */
class VisitReturnsTest {

    private AppointmentRepository appointments;
    private ServiceOrderRepository orders;
    private ApplicationEventPublisher events;
    private VisitReturns returns;

    private Appointment away;
    private final List<ServiceOrder> ordered = new ArrayList<>();
    private final List<Appointment> referrals = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        appointments = mock(AppointmentRepository.class);
        orders = mock(ServiceOrderRepository.class);
        events = mock(ApplicationEventPublisher.class);
        ClinicalEncounterRepository encounters = mock(ClinicalEncounterRepository.class);

        Employee doctor = mock(Employee.class);
        when(doctor.getId()).thenReturn(11L);
        when(doctor.getUser()).thenReturn(new User("+84900000011", null, "h", "BS. A", UserStatus.ACTIVE));
        away = new Appointment();
        ReflectionTestUtils.setField(away, "id", 50L);
        away.setEmployee(doctor);
        away.checkIn(null, null);
        away.callInto(null, null);
        away.sendAway();

        ClinicalEncounter encounter = new ClinicalEncounter();
        ReflectionTestUtils.setField(encounter, "id", 7L);
        when(encounters.findOfOwnAppointment(50L)).thenReturn(List.of(encounter));
        when(orders.findByEncounter(7L)).thenReturn(ordered);
        when(appointments.findReferralsOf(50L)).thenReturn(referrals);
        when(appointments.findOne(any(Specification.class))).thenReturn(Optional.of(away));
        when(appointments.persist(any())).thenAnswer(invocation -> invocation.getArgument(0));

        when(appointments.lockById(50L)).thenReturn(Optional.of(away));
        returns = new VisitReturns(appointments, encounters, orders, events,
                Clock.fixed(Instant.parse("2026-10-28T03:00:00Z"), ZoneOffset.UTC),
                mock(jakarta.persistence.EntityManager.class));
    }

    private ServiceOrder order(String name, ServiceOrderStatus status) {
        ServiceOrder order = new ServiceOrder();
        MedicalService service = new MedicalService();
        ReflectionTestUtils.setField(service, "name", name);
        order.setService(service);
        ReflectionTestUtils.setField(order, "status", status);
        ordered.add(order);
        return order;
    }

    private Appointment referral(AppointmentStatus status) {
        Appointment referral = new Appointment();
        Employee other = mock(Employee.class);
        when(other.getUser()).thenReturn(new User("+84900000022", null, "h", "BS. B", UserStatus.ACTIVE));
        referral.setEmployee(other);
        ReflectionTestUtils.setField(referral, "status", status);
        referrals.add(referral);
        return referral;
    }

    @Test
    @DisplayName("every result in: back in the doctor's queue, first in line, and the room is nudged")
    void returnsWhenResultsAreIn() {
        order("Công thức máu", ServiceOrderStatus.RESULT_READY);
        order("X-quang", ServiceOrderStatus.CANCELLED);

        returns.onOrdersChanged(new VisitEvents.OrdersChanged(50L));

        assertEquals(AppointmentStatus.CHECKED_IN, away.getStatus());
        assertTrue(away.getReturnedAt() != null);
        verify(events).publishEvent(any(ExamRoomEvents.QueueJoined.class));
    }

    @Test
    @DisplayName("a result still to come, or a referral still to happen: it stays away")
    void staysWhileSomethingIsLeft() {
        order("Công thức máu", ServiceOrderStatus.RESULT_READY);
        order("X-quang", ServiceOrderStatus.IN_PROGRESS);
        returns.returnIfDone(50L);
        assertEquals(AppointmentStatus.AWAITING_RESULTS, away.getStatus());

        ordered.clear();
        referral(AppointmentStatus.CHECKED_IN);
        returns.returnIfDone(50L);
        assertEquals(AppointmentStatus.AWAITING_RESULTS, away.getStatus());
        verify(events, never()).publishEvent(any(ExamRoomEvents.QueueJoined.class));
    }

    @Test
    @DisplayName("the referral's visit over (seen, or the patient never went): the origin comes back")
    void returnsWhenReferralEnds() {
        Appointment referral = referral(AppointmentStatus.COMPLETED);
        referral.setReferredFrom(away);
        ReflectionTestUtils.setField(referral, "id", 60L);
        Appointment originLookup = away;
        when(appointments.findOne(any(Specification.class))).thenReturn(Optional.of(referral));
        when(appointments.lockById(50L)).thenReturn(Optional.of(originLookup));

        returns.onVisitEnded(new VisitEvents.VisitEnded(60L));

        assertEquals(AppointmentStatus.CHECKED_IN, away.getStatus());
    }

    @Test
    @DisplayName("tasks: services then referrals, each done or not")
    void tasks() {
        order("Công thức máu", ServiceOrderStatus.RESULT_READY);
        order("X-quang", ServiceOrderStatus.ORDERED);
        referral(AppointmentStatus.CHECKED_IN);

        List<VisitReturns.Task> tasks = returns.tasksOf(away);

        assertEquals(3, tasks.size());
        assertEquals(new VisitReturns.Task("service", "Công thức máu", "result_ready", true), tasks.get(0));
        assertEquals(new VisitReturns.Task("service", "X-quang", "ordered", false), tasks.get(1));
        assertEquals(new VisitReturns.Task("referral", "BS. B", "checked_in", false), tasks.get(2));
    }
}
