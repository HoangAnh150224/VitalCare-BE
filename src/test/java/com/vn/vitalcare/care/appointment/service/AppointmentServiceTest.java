package com.vn.vitalcare.care.appointment.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.care.appointment.dto.BookingRequest;
import com.vn.vitalcare.care.appointment.repository.AppointmentRepository;
import com.vn.vitalcare.care.clinic.service.ScheduleFixture;
import com.vn.vitalcare.care.customer.repository.CustomerRepository;
import com.vn.vitalcare.care.customer.service.CustomerService;
import com.vn.vitalcare.entity.Appointment;
import com.vn.vitalcare.entity.AppointmentStatus;
import com.vn.vitalcare.entity.Customer;
import com.vn.vitalcare.entity.CustomerStatus;
import com.vn.vitalcare.entity.PatientActivationSource;
import com.vn.vitalcare.identity.user.entity.User;
import com.vn.vitalcare.identity.user.entity.UserStatus;
import com.vn.vitalcare.share.exception.ConflictException;
import com.vn.vitalcare.share.exception.FieldValidationException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Booking into a clinic's slots, and the check-in that turns a neutral
 * customer into a patient.
 *
 * <p>Uses the real {@link CustomerService} and the real schedule over mocked
 * repositories, so slot rules and the activation a check-in causes are the
 * production code paths, not stubs of them. The week is the seeded one — see
 * {@link ScheduleFixture}.
 */
class AppointmentServiceTest {

    /** 10:00 on Monday 5 October in Ho Chi Minh City. */
    private static final Instant NOW = Instant.parse("2026-10-05T03:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 5);

    private ScheduleFixture fixture;
    private AppointmentRepository repository;
    private AppointmentService service;
    private Customer customer;

    @BeforeEach
    void setUp() {
        setUp(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void setUp(Clock clock) {
        fixture = new ScheduleFixture(clock);
        repository = fixture.appointments;
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        CustomerRepository customers = Mockito.mock(CustomerRepository.class);
        when(customers.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service = new AppointmentService(
                repository,
                new CustomerService(customers, clock),
                fixture.clinicService,
                fixture.schedule,
                clock);

        customer = new Customer();
        customer.setUser(new User("+84912345678", null, "hash", "Nguyen Van A", UserStatus.ACTIVE));
    }

    @Test
    @DisplayName("a slot later today is booked: end time from the slot, a code, and the customer stays neutral")
    void bookingLaterToday() {
        Appointment booked = service.bookOwn(customer, booking(TODAY, "14:00"));

        assertEquals(AppointmentStatus.SCHEDULED, booked.getStatus());
        assertEquals(ScheduleFixture.CLINIC_ID, booked.getClinic().getClinicId());
        assertEquals(LocalTime.of(14, 30), booked.getEndTime());
        assertTrue(booked.getBookingCode().matches("[" + BookingCodes.ALPHABET + "]{8}"), booked.getBookingCode());
        assertEquals(CustomerStatus.NEUTRAL, customer.getStatus());
    }

    @Test
    @DisplayName("a date in the past is refused on the date field")
    void pastDateIsRefused() {
        assertField("appointmentDate", () -> service.bookOwn(customer, booking(TODAY.minusDays(1), "14:00")));
    }

    @Test
    @DisplayName("a day beyond the booking window is refused on the date field")
    void beyondWindowIsRefused() {
        assertField("appointmentDate", () -> service.bookOwn(customer, booking(TODAY.plusDays(31), "14:00")));
    }

    @Test
    @DisplayName("a day the clinic is closed is refused on the date field")
    void closedDayIsRefused() {
        LocalDate sunday = TODAY.plusDays(6);
        assertField("appointmentDate", () -> service.bookOwn(customer, booking(sunday, "08:00")));
    }

    @Test
    @DisplayName("a time that is not the start of a slot is refused on the time field")
    void offSlotTimeIsRefused() {
        assertField("startTime", () -> service.bookOwn(customer, booking(TODAY, "14:10")));
        assertField("startTime", () -> service.bookOwn(customer, booking(TODAY, "12:00")));
    }

    @Test
    @DisplayName("a slot already started today is refused on the time field")
    void startedSlotIsRefused() {
        assertField("startTime", () -> service.bookOwn(customer, booking(TODAY, "09:00")));
    }

    @Test
    @DisplayName("a slot with every place taken is refused, and nothing is saved")
    void fullSlotIsRefused() {
        fixture.booked.put(LocalTime.of(14, 0), 3L);

        assertThrows(ConflictException.class, () -> service.bookOwn(customer, booking(TODAY, "14:00")));
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("the last place in a slot can still be booked")
    void lastPlaceIsBookable() {
        fixture.booked.put(LocalTime.of(14, 0), 2L);

        assertEquals(AppointmentStatus.SCHEDULED, service.bookOwn(customer, booking(TODAY, "14:00")).getStatus());
    }

    @Test
    @DisplayName("the same customer cannot hold one slot twice")
    void duplicateBookingIsRefused() {
        when(repository.existsByCustomerIdAndAppointmentDateAndStartTimeAndStatusAndDeletedAtIsNull(
                any(), any(), any(), any())).thenReturn(true);

        assertThrows(ConflictException.class, () -> service.bookOwn(customer, booking(TODAY, "14:00")));
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("a code finds its appointment however it was typed")
    void lookupNormalisesTheCode() {
        Appointment appointment = appointmentOn(TODAY);
        when(repository.findByBookingCodeAndDeletedAtIsNull("K7M29QXA")).thenReturn(Optional.of(appointment));

        assertEquals(appointment, service.getByCode(" k7m2-9qxa "));
    }

    @Test
    @DisplayName("checking in a neutral customer makes them a patient, through the check-in")
    void checkInActivatesNeutralCustomer() {
        Appointment appointment = stored(appointmentOn(TODAY));

        Appointment checkedIn = service.checkIn(1L);

        assertEquals(AppointmentStatus.CHECKED_IN, checkedIn.getStatus());
        assertEquals(CustomerStatus.PATIENT, customer.getStatus());
        assertEquals(PatientActivationSource.CHECK_IN, customer.getPatientActivationSource());
        assertEquals(appointment.getCheckedInAt(), customer.getPatientActivatedAt());
    }

    @Test
    @DisplayName("checking in somebody already a patient leaves their activation record alone")
    void checkInKeepsExistingPatient() {
        customer.activateAsPatient(PatientActivationSource.MANUAL, 7L, null);
        stored(appointmentOn(TODAY));

        service.checkIn(1L);

        assertEquals(PatientActivationSource.MANUAL, customer.getPatientActivationSource());
        assertEquals(7L, customer.getPatientActivatedById());
    }

    @Test
    @DisplayName("an appointment cannot be checked in on another day, and nobody is activated")
    void checkInOnAnotherDayIsRefused() {
        stored(appointmentOn(TODAY.plusDays(1)));

        assertThrows(ConflictException.class, () -> service.checkIn(1L));
        assertEquals(CustomerStatus.NEUTRAL, customer.getStatus());
    }

    @Test
    @DisplayName("'today' is the clinic's day, not UTC's: 01:00 local still checks in")
    void todayIsTheClinicsDay() {
        // 18:00 UTC on 4 October is 01:00 on 5 October in Ho Chi Minh City.
        setUp(Clock.fixed(Instant.parse("2026-10-04T18:00:00Z"), ZoneOffset.UTC));
        stored(appointmentOn(TODAY));

        assertEquals(AppointmentStatus.CHECKED_IN, service.checkIn(1L).getStatus());
    }

    @Test
    @DisplayName("a cancelled appointment cannot be checked in")
    void cancelledCannotBeCheckedIn() {
        Appointment appointment = stored(appointmentOn(TODAY));
        appointment.cancel(null, null);

        assertThrows(ConflictException.class, () -> service.checkIn(1L));
        assertEquals(CustomerStatus.NEUTRAL, customer.getStatus());
    }

    @Test
    @DisplayName("a checked-in appointment cannot be cancelled after the fact")
    void checkedInCannotBeCancelled() {
        stored(appointmentOn(TODAY));
        service.checkIn(1L);

        assertThrows(ConflictException.class, () -> service.cancel(1L));
    }

    @Test
    @DisplayName("a scheduled appointment cancels")
    void scheduledCancels() {
        stored(appointmentOn(TODAY.plusDays(2)));

        assertEquals(AppointmentStatus.CANCELLED, service.cancel(1L).getStatus());
    }

    private static void assertField(String field, org.junit.jupiter.api.function.Executable call) {
        FieldValidationException e = assertThrows(FieldValidationException.class, call);
        assertEquals(field, e.field());
    }

    private Appointment stored(Appointment appointment) {
        when(repository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(appointment));
        return appointment;
    }

    private Appointment appointmentOn(LocalDate date) {
        Appointment appointment = new Appointment();
        appointment.setCustomer(customer);
        appointment.setClinic(fixture.clinic);
        appointment.setAppointmentDate(date);
        appointment.setStartTime(LocalTime.of(14, 0));
        return appointment;
    }

    private static BookingRequest booking(LocalDate date, String start) {
        return new BookingRequest(ScheduleFixture.CLINIC_ID, date, LocalTime.parse(start), "Check-up");
    }
}
