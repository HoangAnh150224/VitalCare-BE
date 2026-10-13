package com.vn.vitalcare.care.appointment.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.care.ClinicRowLevel;
import com.vn.vitalcare.care.appointment.dto.AppointmentUpdateRequest;
import com.vn.vitalcare.care.appointment.dto.BookingRequest;
import com.vn.vitalcare.care.appointment.dto.RescheduleRequest;
import com.vn.vitalcare.care.appointment.dto.StaffBookingRequest;
import com.vn.vitalcare.care.appointment.repository.AppointmentRepository;
import com.vn.vitalcare.care.appointment.service.impl.AppointmentServiceImpl;
import com.vn.vitalcare.care.clinic.ClinicProperties;
import com.vn.vitalcare.care.clinic.repository.ClinicRepository;
import com.vn.vitalcare.care.clinic.service.ClinicService;
import com.vn.vitalcare.care.clinic.service.impl.ClinicServiceImpl;
import com.vn.vitalcare.care.customer.repository.CustomerRepository;
import com.vn.vitalcare.care.customer.service.impl.CustomerServiceImpl;
import com.vn.vitalcare.care.schedule.repository.DoctorScheduleRepository;
import com.vn.vitalcare.care.schedule.repository.DoctorWorkingHoursRepository;
import com.vn.vitalcare.care.schedule.service.impl.DoctorAvailabilityServiceImpl;
import com.vn.vitalcare.care.staff.service.ClinicScope;
import com.vn.vitalcare.care.staff.service.EmployeeService;
import com.vn.vitalcare.entity.Appointment;
import com.vn.vitalcare.entity.AppointmentStatus;
import com.vn.vitalcare.entity.Clinic;
import com.vn.vitalcare.entity.Customer;
import com.vn.vitalcare.entity.CustomerStatus;
import com.vn.vitalcare.entity.DoctorSchedule;
import com.vn.vitalcare.entity.DoctorWorkingHours;
import com.vn.vitalcare.entity.Employee;
import com.vn.vitalcare.entity.PatientActivationSource;
import com.vn.vitalcare.identity.user.entity.User;
import com.vn.vitalcare.identity.user.entity.UserStatus;
import com.vn.vitalcare.share.exception.ConflictException;
import com.vn.vitalcare.share.exception.FieldValidationException;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import com.vn.vitalcare.share.security.rowlevel.RowLevelViolationException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.domain.Specification;

/**
 * Registering a visit with a doctor for a day, the queue number that gives,
 * and what happens to the visit afterwards.
 *
 * <p>Uses the real availability, clinic and customer services over mocked
 * repositories, so the size of a doctor's day, the number issued and the
 * activation a check-in causes are the production code paths. The doctor
 * works Monday to Friday, 08:00–12:00 and 13:00–17:00, half an hour a
 * patient: sixteen turns a day.
 */
class AppointmentServiceTest {

    /** 10:00 on Monday 5 October in Ho Chi Minh City. */
    private static final Instant NOW = Instant.parse("2026-10-05T03:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 5);
    private static final LocalDate TUESDAY = TODAY.plusDays(1);
    private static final UUID CLINIC = UUID.fromString("5b1f8c2e-6a3d-4f7e-9c41-2d8e0a7b3c19");
    private static final UUID OTHER_CLINIC = UUID.fromString("c2a7e4d1-3b8f-4c62-9a15-7e0d3f9b2a48");
    private static final long DOCTOR = 11L;

    private AppointmentRepository repository;
    private AppointmentService service;
    private Customer customer;
    private Employee doctor;
    private Clinic clinic;
    /** The signed-in receptionist's clinic; null for somebody tied to none. */
    private UUID scopedTo;
    private ClinicRowLevel rowLevel;
    /** What the doctor has blocked on whichever day is asked about. */
    private final List<DoctorSchedule> blocked = new ArrayList<>();
    /** The doctor's day so far: people still to be seen, and the highest number issued. */
    private long held;
    private int issued;

    @BeforeEach
    void setUp() {
        setUp(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @SuppressWarnings("unchecked")
    private void setUp(Clock clock) {
        repository = mock(AppointmentRepository.class);
        when(repository.persist(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.countWaiting(eq(DOCTOR), any())).thenAnswer(invocation -> held);
        when(repository.maxQueueNumber(eq(DOCTOR), any())).thenAnswer(invocation -> issued);

        clinic = new Clinic();
        clinic.setClinicId(CLINIC);
        clinic.setClinicName("VitalCare Clinic");
        ClinicRepository clinics = mock(ClinicRepository.class);
        when(clinics.findByClinicIdAndDeletedAtIsNull(CLINIC)).thenReturn(Optional.of(clinic));
        ClinicService clinicService = new ClinicServiceImpl(
                clinics, new ClinicProperties(ZoneId.of("Asia/Ho_Chi_Minh"), 30), clock);

        doctor = mock(Employee.class);
        when(doctor.getId()).thenReturn(DOCTOR);
        when(doctor.getClinicId()).thenReturn(CLINIC);
        EmployeeService employees = mock(EmployeeService.class);
        when(employees.lockDoctor(DOCTOR)).thenReturn(doctor);
        when(employees.lockDoctor(99L)).thenThrow(new ResourceNotFoundException("Doctor", 99L));

        DoctorWorkingHoursRepository hours = mock(DoctorWorkingHoursRepository.class);
        when(hours.findDay(eq(DOCTOR), anyShort())).thenAnswer(invocation -> {
            DayOfWeek day = DayOfWeek.of((short) invocation.getArgument(1));
            return day.getValue() > DayOfWeek.FRIDAY.getValue() ? List.of() : List.of(
                    new DoctorWorkingHours(doctor, day, LocalTime.of(8, 0), LocalTime.of(12, 0), 30),
                    new DoctorWorkingHours(doctor, day, LocalTime.of(13, 0), LocalTime.of(17, 0), 30));
        });
        DoctorScheduleRepository timeOff = mock(DoctorScheduleRepository.class);
        when(timeOff.findAll(any(Specification.class))).thenAnswer(invocation -> blocked);

        CustomerRepository customers = mock(CustomerRepository.class);
        when(customers.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        ClinicScope scope = () -> Optional.ofNullable(scopedTo);
        rowLevel = new ClinicRowLevel(() -> scopedTo);

        service = new AppointmentServiceImpl(
                repository,
                new CustomerServiceImpl(customers, scope, rowLevel, clock),
                clinicService,
                employees,
                new DoctorAvailabilityServiceImpl(hours, timeOff, repository, employees, clinicService, scope, clock),
                rowLevel,
                clock);

        customer = new Customer();
        customer.setUser(new User("+84912345678", null, "hash", "Nguyen Van A", UserStatus.ACTIVE));
        when(customers.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(customer));
    }

    // --- registering ----------------------------------------------------------

    @Test
    @DisplayName("the first to register with a doctor for a day gets number 1 and the day's first turn")
    void firstRegistration() {
        Appointment booked = service.bookOwn(customer, booking(TUESDAY));

        assertEquals(1, booked.getQueueNumber());
        assertEquals(LocalTime.of(8, 0), booked.getStartTime());
        assertEquals(LocalTime.of(8, 30), booked.getEndTime());
        assertEquals(AppointmentStatus.SCHEDULED, booked.getStatus());
        assertEquals(doctor, booked.getEmployee());
        assertEquals(CLINIC, booked.getClinicId());
        assertEquals("Check-up", booked.getReason());
        assertTrue(booked.getBookingCode().matches("[" + BookingCodes.ALPHABET + "]{8}"), booked.getBookingCode());
        assertEquals(CustomerStatus.NEUTRAL, customer.getStatus());
    }

    @Test
    @DisplayName("the next number follows the last one issued, at that number's turn; a cancelled number is not reused")
    void nextNumber() {
        // Six issued and all still held: number 7 is the 11:00 turn.
        issued = 6;
        held = 6;
        Appointment seventh = service.bookOwn(customer, booking(TUESDAY));
        assertEquals(7, seventh.getQueueNumber());
        assertEquals(LocalTime.of(11, 0), seventh.getStartTime());

        // One of the six cancelled: its turn is free again, its number is not.
        held = 5;
        assertEquals(7, service.bookOwn(customer, booking(TUESDAY)).getQueueNumber());
    }

    @Test
    @DisplayName("registering today for a doctor nobody has seen yet expects the next turn still ahead, not 08:00")
    void todaySkipsTurnsAlreadyPast() {
        // 10:00 now: the 10:00 turn has started, 10:30 is the first ahead.
        Appointment booked = service.bookOwn(customer, booking(TODAY));

        assertEquals(1, booked.getQueueNumber());
        assertEquals(LocalTime.of(10, 30), booked.getStartTime());

        // Two people already waiting: the next number goes behind them, whatever it is.
        held = 2;
        issued = 5;
        assertEquals(LocalTime.of(11, 30), service.bookOwn(customer, booking(TODAY)).getStartTime());

        // Eleven turns are still ahead today (10:30 to 16:30); with eleven waiting the day is full.
        held = 11;
        assertThrows(ConflictException.class, () -> service.bookOwn(customer, booking(TODAY)));
    }

    @Test
    @DisplayName("a day with every turn held is refused, and nothing is saved")
    void fullDayIsRefused() {
        held = 16;
        issued = 16;

        assertThrows(ConflictException.class, () -> service.bookOwn(customer, booking(TUESDAY)));
        verify(repository, never()).persist(any());
    }

    @Test
    @DisplayName("blocked time takes its turns out of the day: fewer patients, and the first number starts after it")
    void blockedTimeShrinksTheDay() {
        DoctorSchedule morning = new DoctorSchedule();
        morning.setStartTime(LocalTime.of(8, 0));
        morning.setEndTime(LocalTime.of(12, 0));
        blocked.add(morning);

        assertEquals(LocalTime.of(13, 0), service.bookOwn(customer, booking(TUESDAY)).getStartTime());

        held = 8;
        assertThrows(ConflictException.class, () -> service.bookOwn(customer, booking(TUESDAY)));
    }

    @Test
    @DisplayName("a day the doctor does not work, a past day and a day beyond the window are refused on the date")
    void unavailableDays() {
        LocalDate sunday = TODAY.plusDays(6);
        assertField("appointmentDate", () -> service.bookOwn(customer, booking(sunday)));
        assertField("appointmentDate", () -> service.bookOwn(customer, booking(TODAY.minusDays(1))));
        assertField("appointmentDate", () -> service.bookOwn(customer, booking(TODAY.plusDays(31))));
    }

    @Test
    @DisplayName("once the doctor's last turn of today has started, today is over")
    void dayAlreadyOver() {
        // 17:30 in Ho Chi Minh City.
        setUp(Clock.fixed(Instant.parse("2026-10-05T10:30:00Z"), ZoneOffset.UTC));

        assertThrows(ConflictException.class, () -> service.bookOwn(customer, booking(TODAY)));
    }

    @Test
    @DisplayName("somebody who is not one of the clinic's doctors is refused on the doctor")
    void unknownDoctor() {
        assertField("employeeId", () -> service.bookOwn(customer, new BookingRequest(99L, TUESDAY, "Check-up")));
    }

    @Test
    @DisplayName("the same customer cannot register twice with one doctor for one day")
    void duplicateIsRefused() {
        when(repository.existsByCustomerIdAndEmployeeIdAndAppointmentDateAndStatusAndDeletedAtIsNull(
                any(), any(), any(), any())).thenReturn(true);

        assertThrows(ConflictException.class, () -> service.bookOwn(customer, booking(TUESDAY)));
        verify(repository, never()).persist(any());
    }

    // --- the desk -------------------------------------------------------------

    @Test
    @DisplayName("a receptionist registers a visit with a doctor of their own clinic, with a note")
    void deskRegistersAtOwnClinic() {
        scopedTo = CLINIC;
        Appointment booked = service.bookFor(new StaffBookingRequest(1L, DOCTOR, TUESDAY, "Check-up", " Gọi trước "));

        assertEquals(CLINIC, booked.getClinicId());
        assertEquals("Gọi trước", booked.getNote());
    }

    @Test
    @DisplayName("a receptionist cannot register with another clinic's doctor: the write policy refuses the saved row")
    void deskCannotRegisterAtAnotherClinic() {
        scopedTo = OTHER_CLINIC;
        RowLevelViolationException e = assertThrows(RowLevelViolationException.class,
                () -> service.bookFor(new StaffBookingRequest(1L, DOCTOR, TUESDAY, "Check-up", null)));
        assertEquals("clinicId", e.field());
        // Checked after the flush, as the framework does; the exception rolls
        // the insert back with the transaction.
        verify(repository).flush();
    }

    @Test
    @DisplayName("a customer registers their own visit without holding any row-level scope")
    void customerIgnoresStaffScope() {
        scopedTo = OTHER_CLINIC;
        assertEquals(CLINIC, service.bookOwn(customer, booking(TUESDAY)).getClinicId());
    }

    @Test
    @DisplayName("another clinic's appointment is not found for a receptionist, by id or by code, to read or to change")
    void otherClinicIsNotFound() {
        Appointment appointment = stored(appointmentOn(TODAY));

        scopedTo = CLINIC;
        assertEquals(appointment, service.get(1L));
        assertEquals(appointment, service.getByCode(" k7m2-9qxa "));

        scopedTo = OTHER_CLINIC;
        assertThrows(ResourceNotFoundException.class, () -> service.get(1L));
        assertThrows(ResourceNotFoundException.class, () -> service.getByCode("K7M29QXA"));
        assertThrows(ResourceNotFoundException.class, () -> service.checkIn(1L));
        assertThrows(ResourceNotFoundException.class, () -> service.cancel(1L, "reason"));
        assertThrows(ResourceNotFoundException.class, () -> service.complete(1L));
        assertEquals(AppointmentStatus.SCHEDULED, appointment.getStatus());
        verify(repository, never()).persist(any());
    }

    @Test
    @DisplayName("the desk corrects the reason and its note while the visit is still open")
    void updateReasonAndNote() {
        Appointment appointment = stored(appointmentOn(TUESDAY));

        service.update(1L, new AppointmentUpdateRequest(" Tái khám ", " "));
        assertEquals("Tái khám", appointment.getReason());
        assertEquals(null, appointment.getNote());

        appointment.cancel(null, null, null);
        assertThrows(ConflictException.class, () -> service.update(1L, new AppointmentUpdateRequest("x", null)));
    }

    // --- check-in, completion, no-show ----------------------------------------

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
    @DisplayName("an appointment is checked in on its own day only, as the clinic counts days, and not once cancelled")
    void checkInRules() {
        stored(appointmentOn(TUESDAY));
        assertThrows(ConflictException.class, () -> service.checkIn(1L));
        assertEquals(CustomerStatus.NEUTRAL, customer.getStatus());

        Appointment cancelled = stored(appointmentOn(TODAY));
        cancelled.cancel(null, null, null);
        assertThrows(ConflictException.class, () -> service.checkIn(1L));

        // 18:00 UTC on 4 October is 01:00 on 5 October in Ho Chi Minh City.
        setUp(Clock.fixed(Instant.parse("2026-10-04T18:00:00Z"), ZoneOffset.UTC));
        stored(appointmentOn(TODAY));
        assertEquals(AppointmentStatus.CHECKED_IN, service.checkIn(1L).getStatus());
    }

    @Test
    @DisplayName("a visit completes only after check-in, by the desk or by its encounter being completed")
    @SuppressWarnings("unchecked")
    void completes() {
        Appointment appointment = stored(appointmentOn(TODAY));
        assertThrows(ConflictException.class, () -> service.complete(1L));

        service.checkIn(1L);
        assertEquals(AppointmentStatus.COMPLETED, service.complete(1L).getStatus());
        assertThrows(ConflictException.class, () -> service.complete(1L));

        // The encounter's path: no scope, and silent unless the visit is checked in.
        Appointment second = appointmentOn(TODAY);
        when(repository.findOne(any(Specification.class))).thenReturn(Optional.of(second));
        service.completeForEncounter(2L);
        assertEquals(AppointmentStatus.SCHEDULED, second.getStatus());
        second.checkIn(null, null);
        service.completeForEncounter(2L);
        assertEquals(AppointmentStatus.COMPLETED, second.getStatus());
        assertEquals(AppointmentStatus.COMPLETED, appointment.getStatus());
    }

    @Test
    @DisplayName("a no-show is a registered visit whose day has come; not a future one, not one already checked in")
    void noShow() {
        stored(appointmentOn(TUESDAY));
        assertThrows(ConflictException.class, () -> service.markNoShow(1L));

        Appointment today = stored(appointmentOn(TODAY));
        assertEquals(AppointmentStatus.NO_SHOW, service.markNoShow(1L).getStatus());
        assertThrows(ConflictException.class, () -> service.markNoShow(1L));
        assertThrows(ConflictException.class, () -> service.checkIn(1L));
        assertEquals(AppointmentStatus.NO_SHOW, today.getStatus());
    }

    // --- cancelling and rescheduling ------------------------------------------

    @Test
    @DisplayName("a registered appointment cancels with the desk's reason; a checked-in one does not")
    void cancels() {
        stored(appointmentOn(TODAY.plusDays(2)));
        Appointment cancelled = service.cancel(1L, " Bác sĩ nghỉ đột xuất ");
        assertEquals(AppointmentStatus.CANCELLED, cancelled.getStatus());
        assertEquals("Bác sĩ nghỉ đột xuất", cancelled.getCancelReason());

        stored(appointmentOn(TODAY));
        service.checkIn(1L);
        assertThrows(ConflictException.class, () -> service.cancel(1L, "reason"));
    }

    @Test
    @DisplayName("BR-26: an appointment expected in under two hours can no longer be cancelled or rescheduled")
    void lateChangeIsRefused() {
        // NOW is 10:00 at the clinic; 11:00 today is one hour away.
        Appointment soon = stored(appointmentOn(TODAY));
        soon.setStartTime(LocalTime.of(11, 0));

        assertThrows(ConflictException.class, () -> service.cancel(1L, "reason"));
        assertThrows(ConflictException.class, () -> service.reschedule(1L, new RescheduleRequest(null, TUESDAY)));
        assertEquals(AppointmentStatus.SCHEDULED, soon.getStatus());
        assertEquals(TODAY, soon.getAppointmentDate());
    }

    @Test
    @DisplayName("rescheduling to another day gives that day's next number and keeps the code; the same day is refused")
    void reschedules() {
        Appointment appointment = stored(appointmentOn(TODAY.plusDays(2)));
        assertField("appointmentDate", () -> service.reschedule(1L, new RescheduleRequest(null, TODAY.plusDays(2))));
        assertField("appointmentDate",
                () -> service.reschedule(1L, new RescheduleRequest(DOCTOR, TODAY.plusDays(2))));

        issued = 3;
        held = 3;
        Appointment moved = service.reschedule(1L, new RescheduleRequest(null, TODAY.plusDays(3)));

        assertEquals(TODAY.plusDays(3), moved.getAppointmentDate());
        assertEquals(4, moved.getQueueNumber());
        assertEquals(LocalTime.of(9, 30), moved.getStartTime());
        assertEquals("K7M29QXA", moved.getBookingCode());
        assertEquals(AppointmentStatus.SCHEDULED, appointment.getStatus());
    }

    private static void assertField(String field, org.junit.jupiter.api.function.Executable call) {
        FieldValidationException e = assertThrows(FieldValidationException.class, call);
        assertEquals(field, e.field());
    }

    /**
     * The one appointment the repository holds, found by id or by code through
     * the caller's scope -- which a mock cannot run, so it is answered with the
     * same rule in memory.
     */
    @SuppressWarnings("unchecked")
    private Appointment stored(Appointment appointment) {
        when(repository.findOne(any(Specification.class)))
                .thenAnswer(invocation -> Optional.of(appointment).filter(rowLevel::canRead));
        return appointment;
    }

    /** Number 13 with the doctor that day: the 14:00 turn. */
    private Appointment appointmentOn(LocalDate date) {
        Appointment appointment = new Appointment();
        appointment.setCustomer(customer);
        appointment.setEmployee(doctor);
        appointment.setClinic(clinic);
        appointment.setAppointmentDate(date);
        appointment.setQueueNumber(13);
        appointment.setStartTime(LocalTime.of(14, 0));
        appointment.setEndTime(LocalTime.of(14, 30));
        appointment.setBookingCode("K7M29QXA");
        appointment.setReason("Check-up");
        return appointment;
    }

    private static BookingRequest booking(LocalDate date) {
        return new BookingRequest(DOCTOR, date, "Check-up");
    }
}
