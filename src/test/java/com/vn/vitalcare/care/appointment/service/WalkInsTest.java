package com.vn.vitalcare.care.appointment.service;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.care.billing.invoice.Invoices;
import com.vn.vitalcare.care.clinic.service.ClinicService;
import com.vn.vitalcare.care.customer.service.CustomerService;
import com.vn.vitalcare.entity.Appointment;
import com.vn.vitalcare.entity.Customer;
import com.vn.vitalcare.share.exception.ConflictException;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** UC-02 B1, "convert to walk-in": which visits may be replaced, and in what order the fee and the old visit move. */
class WalkInsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 7);

    private AppointmentService appointments;
    private Invoices invoices;
    private WalkIns walkIns;
    private Appointment fresh;

    @BeforeEach
    void setUp() {
        appointments = mock(AppointmentService.class);
        invoices = mock(Invoices.class);
        ClinicService clinics = mock(ClinicService.class);
        when(clinics.today()).thenReturn(TODAY);
        walkIns = new WalkIns(mock(CustomerService.class), appointments, clinics, invoices);
        fresh = new Appointment();
        when(appointments.walkIn(any())).thenReturn(fresh);
    }

    @Test
    @DisplayName("a visit still booked for a past day: a walk-in today, the paid fee carried over, then the old one a no-show")
    void replacesALateVisit() {
        Appointment late = visit(TODAY.minusDays(1));

        assertSame(fresh, walkIns.replace(1L));

        var order = inOrder(appointments, invoices);
        order.verify(appointments).walkIn(any());
        order.verify(invoices).carryExamPaid(1L, fresh);
        order.verify(appointments).markNoShow(1L);
    }

    @Test
    @DisplayName("a cancelled visit is replaced and left as it is")
    void replacesACancelledVisit() {
        Appointment cancelled = visit(TODAY);
        cancelled.cancel(null, null, "Đổi ý");

        walkIns.replace(1L);

        verify(invoices).carryExamPaid(1L, fresh);
        verify(appointments, never()).markNoShow(any());
    }

    @Test
    @DisplayName("a visit booked for today is checked in as it is, not replaced")
    void refusesAVisitThatCanBeCheckedIn() {
        visit(TODAY);

        assertThrows(ConflictException.class, () -> walkIns.replace(1L));
        verify(appointments, never()).walkIn(any());
    }

    private Appointment visit(LocalDate day) {
        Customer patient = new Customer();
        ReflectionTestUtils.setField(patient, "id", 3L);
        Appointment visit = new Appointment();
        ReflectionTestUtils.setField(visit, "id", 1L);
        visit.setCustomer(patient);
        visit.setAppointmentDate(day);
        visit.setReason("Sốt");
        visit.setBookingCode("K7M29QXA");
        when(appointments.get(1L)).thenReturn(visit);
        return visit;
    }
}
