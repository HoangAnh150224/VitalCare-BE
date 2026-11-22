package com.vn.vitalcare.care.appointment.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.care.appointment.dto.BookingRequest;
import com.vn.vitalcare.care.appointment.repository.AppointmentRepository;
import com.vn.vitalcare.care.clinic.service.ClinicService;
import com.vn.vitalcare.care.customer.service.CustomerService;
import com.vn.vitalcare.care.examroom.service.ExamRoomEvents;
import com.vn.vitalcare.care.examroom.service.ExamRoomService;
import com.vn.vitalcare.care.schedule.service.DoctorAvailabilityService;
import com.vn.vitalcare.care.schedule.service.Turn;
import com.vn.vitalcare.care.staff.service.EmployeeService;
import com.vn.vitalcare.entity.Appointment;
import com.vn.vitalcare.entity.AppointmentStatus;
import com.vn.vitalcare.entity.Clinic;
import com.vn.vitalcare.entity.Customer;
import com.vn.vitalcare.entity.Employee;
import com.vn.vitalcare.identity.rowlevel.service.RowLevelSecurity;
import com.vn.vitalcare.share.exception.ConflictException;
import com.vn.vitalcare.share.exception.FieldValidationException;
import jakarta.validation.Validation;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.jpa.domain.Specification;

/**
 * Appointment flow (FE-10 Create Appointment, Walk-in Registration, Appointment Check-in, Daily Examination Queue).
 * Expectations follow the business flow: pay first, then the queue. Tests marked "business rule" record where the
 * code does not follow it yet.
 */
class AppointmentFlowUnitTest {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 2);
    private static final UUID CLINIC = UUID.fromString("5b1f8c2e-6a3d-4f7e-9c41-2d8e0a7b3c19");

    private AppointmentRepository repo;
    private CustomerService customers;
    private ClinicService clinics;
    private EmployeeService employees;
    private DoctorAvailabilityService availability;
    private RowLevelSecurity rowLevel;
    private ApplicationEventPublisher events;
    private AppointmentServiceImpl service;
    private Employee doctor;
    private Customer patient;
    private final List<Turn> turns = List.of(new Turn(LocalTime.of(8, 0), LocalTime.of(8, 30)),
            new Turn(LocalTime.of(8, 30), LocalTime.of(9, 0)));

    @BeforeEach
    void setUp() {
        repo = mock(AppointmentRepository.class);
        customers = mock(CustomerService.class);
        clinics = mock(ClinicService.class);
        employees = mock(EmployeeService.class);
        availability = mock(DoctorAvailabilityService.class);
        rowLevel = mock(RowLevelSecurity.class);
        events = mock(ApplicationEventPublisher.class);
        Clock clock = Clock.fixed(TODAY.atTime(7, 0).atZone(VN).toInstant(), VN);
        service = new AppointmentServiceImpl(repo, customers, clinics, employees, availability, rowLevel,
                mock(ExamRoomService.class), events, clock);

        when(clinics.today()).thenReturn(TODAY);
        when(clinics.timeZone()).thenReturn(VN);
        when(clinics.isWithinBookingWindow(any())).thenReturn(true);
        Clinic clinic = new Clinic();
        clinic.setClinicId(CLINIC);
        clinic.setOperatingStatus("OPERATING");
        when(clinics.get(CLINIC)).thenReturn(clinic);
        doctor = mock(Employee.class);
        when(doctor.getId()).thenReturn(1L);
        when(doctor.getClinicId()).thenReturn(CLINIC);
        when(employees.doctorsOf(CLINIC)).thenReturn(List.of(doctor));
        when(availability.turns(doctor, TODAY)).thenReturn(turns);
        when(repo.persist(any())).thenAnswer(i -> i.getArgument(0));
        when(repo.existsByBookingCode(anyString())).thenReturn(false);
        patient = mock(Customer.class);
        when(patient.getId()).thenReturn(10L);
        when(patient.isCompleteForVisit()).thenReturn(true);
    }

    private BookingRequest booking(LocalDate date) {
        return new BookingRequest(null, null, CLINIC, date, "Đau đầu, sốt nhẹ");
    }

    // ---------------------------------------------------------------- Create Appointment / Walk-in
    @Nested
    @DisplayName("Create Appointment (bookOwn / bookFor)")
    class Booking {

        @Test void apt01_bookingGetsClinicsNextNumberAndCode() {
            when(repo.maxQueueNumberAtClinic(CLINIC, TODAY)).thenReturn(4);
            Appointment a = service.bookOwn(patient, booking(TODAY));
            assertEquals(5, a.getQueueNumber());
            assertNull(a.getEmployee(), "shared line: the room that calls the visit gives it its doctor");
            assertNull(a.getStartTime());
            assertEquals(AppointmentStatus.BOOKED, a.getStatus());
            assertNotNull(a.getBookingCode());
            assertEquals("Đau đầu, sốt nhẹ", a.getReason());
        }

        @Test void apt02_profileWithoutBirthDateOrGenderRefused() {
            when(patient.isCompleteForVisit()).thenReturn(false);
            FieldValidationException e = assertThrows(FieldValidationException.class, () -> service.bookOwn(patient, booking(TODAY)));
            assertEquals("patientId", e.field());
            verify(repo, never()).persist(any());
        }

        @Test void apt03_pastDateRefused() {
            FieldValidationException e = assertThrows(FieldValidationException.class,
                    () -> service.bookOwn(patient, booking(TODAY.minusDays(1))));
            assertEquals("appointmentDate", e.field());
        }

        @Test void apt04_beyondBookingWindowRefused() {
            when(clinics.isWithinBookingWindow(TODAY.plusDays(60))).thenReturn(false);
            when(clinics.lastBookableDate()).thenReturn(TODAY.plusDays(30));
            FieldValidationException e = assertThrows(FieldValidationException.class,
                    () -> service.bookOwn(patient, booking(TODAY.plusDays(60))));
            assertTrue(e.getMessage().startsWith("Registration is open up to"));
        }

        @Test void apt05_doctorNotWorkingThatDayRefused() {
            when(availability.turns(doctor, TODAY)).thenReturn(List.of());
            FieldValidationException e = assertThrows(FieldValidationException.class, () -> service.bookOwn(patient, booking(TODAY)));
            assertEquals("No doctor sees patients at that clinic on that day", e.getMessage());
        }

        @Test void apt06_secondVisitSameClinicSameDayRefused() {
            when(repo.existsScheduledAtClinic(10L, CLINIC, TODAY, -1L)).thenReturn(true);
            ConflictException e = assertThrows(ConflictException.class, () -> service.bookOwn(patient, booking(TODAY)));
            assertEquals("There is already a visit registered at that clinic on that day", e.getMessage());
        }

        @Test void apt07_dailyCapacityReachedRefused() {
            when(repo.countActiveAtClinic(CLINIC, TODAY, -1L)).thenReturn(2L);
            FieldValidationException e = assertThrows(FieldValidationException.class, () -> service.bookOwn(patient, booking(TODAY)));
            assertEquals("No doctor has a turn left at that clinic on that day", e.getMessage());
        }

        @Test void apt08_closedClinicTakesNoVisits() {
            clinics.get(CLINIC).setOperatingStatus("INACTIVE");
            FieldValidationException e = assertThrows(FieldValidationException.class, () -> service.bookOwn(patient, booking(TODAY)));
            assertEquals("That clinic is not taking visits", e.getMessage());
        }

        @Test void apt09_bookingRequestValidation() {
            var v = Validation.buildDefaultValidatorFactory().getValidator()
                    .validate(new BookingRequest(null, null, null, null, " ")).stream()
                    .map(x -> x.getPropertyPath() + ": " + x.getMessage()).collect(Collectors.toSet());
            assertEquals(Set.of("appointmentDate: A date is required", "reason: A reason for the visit is required"), v);
        }
    }

    // ---------------------------------------------------------------- Appointment Check-in / Queue
    @Nested
    @DisplayName("Appointment Check-in (checkIn)")
    class CheckIn {

        private Appointment visit;

        @BeforeEach
        void scheduledToday() {
            visit = new Appointment();
            visit.setCustomer(patient);
            visit.setEmployee(doctor);
            visit.setClinic(clinics.get(CLINIC));
            visit.setAppointmentDate(TODAY);
            visit.setQueueNumber(1);
            when(rowLevel.byId(eq(Appointment.class), any(), eq(7L))).thenReturn((root, q, cb) -> null);
            when(repo.findOne(any(Specification.class))).thenReturn(Optional.of(visit));
        }

        @Test void chk01_checkInTodayJoinsTheQueueAndActivatesThePatient() {
            Appointment a = service.checkIn(7L);
            assertEquals(AppointmentStatus.CHECKED_IN, a.getStatus());
            assertNotNull(a.getCheckedInAt());
            verify(customers).activateIfNeutral(eq(patient), any());
            ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
            verify(events, org.mockito.Mockito.times(2)).publishEvent(event.capture());
            assertTrue(event.getAllValues().stream().anyMatch(e -> e instanceof ExamRoomEvents.QueueJoined));
        }

        @Test void chk02_onlyOnTheAppointmentsOwnDate() {
            visit.setAppointmentDate(TODAY.plusDays(1));
            ConflictException e = assertThrows(ConflictException.class, () -> service.checkIn(7L));
            assertEquals("An appointment can only be checked in on its own date", e.getMessage());
        }

        @Test void chk03_notTwice() {
            service.checkIn(7L);
            ConflictException e = assertThrows(ConflictException.class, () -> service.checkIn(7L));
            assertEquals("Only a registered appointment can be checked in", e.getMessage());
        }

        /**
         * Business rule (Daily Examination Queue): a patient enters the queue only once checked in AND paid.
         * Nothing has been paid for this visit, so checking in must not announce a place in the queue.
         */
        @Test void que01_businessRule_unpaidCheckInDoesNotJoinTheQueue() {
            Appointment a = service.checkIn(7L);
            assertEquals(AppointmentStatus.CHECKED_IN, a.getStatus());
            assertNull(service.queueAhead(a), "not in the line before the fee is paid");
        }

        /** Paid online before arriving: checking in puts the visit straight in the line. */
        @Test void que03_paidCheckInIsReadyForExamination() {
            visit.markPaid(java.time.OffsetDateTime.now());
            Appointment a = service.checkIn(7L);
            assertEquals(AppointmentStatus.READY_FOR_EXAMINATION, a.getStatus());
        }
    }

    @Test
    void que02_queueAheadIsNullForAVisitNotCheckedIn() {
        Appointment scheduled = new Appointment();
        scheduled.setAppointmentDate(TODAY);
        assertEquals(null, service.queueAhead(scheduled));
        assertFalse(scheduled.getStatus() == AppointmentStatus.CHECKED_IN);
    }

}
