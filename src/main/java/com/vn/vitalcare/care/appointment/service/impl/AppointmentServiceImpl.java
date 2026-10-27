package com.vn.vitalcare.care.appointment.service.impl;

import com.vn.vitalcare.care.appointment.dto.AppointmentUpdateRequest;
import com.vn.vitalcare.care.appointment.dto.BookingRequest;
import com.vn.vitalcare.care.appointment.dto.RescheduleRequest;
import com.vn.vitalcare.care.appointment.dto.StaffBookingRequest;
import com.vn.vitalcare.care.appointment.repository.AppointmentRepository;
import com.vn.vitalcare.care.appointment.repository.AppointmentSpecifications;
import com.vn.vitalcare.care.appointment.service.AppointmentService;
import com.vn.vitalcare.care.appointment.service.BookingCodes;
import com.vn.vitalcare.care.clinic.service.ClinicService;
import com.vn.vitalcare.care.customer.service.CustomerService;
import com.vn.vitalcare.care.examroom.service.ExamRoomEvents;
import com.vn.vitalcare.care.examroom.service.ExamRoomService;
import com.vn.vitalcare.care.schedule.service.DoctorAvailabilityService;
import com.vn.vitalcare.care.schedule.service.Turn;
import com.vn.vitalcare.care.staff.service.EmployeeService;
import com.vn.vitalcare.entity.Appointment;
import com.vn.vitalcare.entity.AppointmentStatus;
import com.vn.vitalcare.entity.Customer;
import com.vn.vitalcare.entity.Employee;
import com.vn.vitalcare.entity.PatientActivationSource;
import com.vn.vitalcare.identity.rowlevel.service.RowLevelSecurity;
import com.vn.vitalcare.share.data.BaseEntitySpecifications;
import com.vn.vitalcare.share.exception.ConflictException;
import com.vn.vitalcare.share.exception.FieldValidationException;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import com.vn.vitalcare.share.security.CurrentUser;
import com.vn.vitalcare.share.security.rowlevel.Action;
import com.vn.vitalcare.share.web.ListParams;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The {@link AppointmentService} the application runs on. */
@Service
@Transactional(readOnly = true)
public class AppointmentServiceImpl implements AppointmentService {

    /** Sort properties the list endpoints accept; anything else is ignored. */
    private static final Set<String> SORTABLE =
            Set.of("id", "appointmentDate", "queueNumber", "startTime", "status", "createdAt");

    /** Newest day first, and within a day in the order the numbers are called. */
    private static final Sort DEFAULT_SORT = Sort.by(
            Sort.Order.desc("appointmentDate"), Sort.Order.asc("startTime"), Sort.Order.asc("queueNumber"),
            Sort.Order.desc("id"));

    /** Generating a code that is already taken is a one-in-billions event; a few retries cover it. */
    private static final int CODE_ATTEMPTS = 5;

    private final AppointmentRepository repository;
    private final CustomerService customerService;
    private final ClinicService clinicService;
    private final EmployeeService employeeService;
    private final DoctorAvailabilityService availability;
    // Which clinics' appointments the staff screens reach is a row-level
    // policy on "appointments". The customer's own paths (bookOwn, getOwn,
    // cancelOwn, rescheduleOwn) are pinned to the customer and do not go
    // through it: a customer holds no scope here, and the default is none.
    private final RowLevelSecurity rowLevel;
    // A visit ending frees the room it was in; a check-in may fill one.
    private final ExamRoomService examRooms;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public AppointmentServiceImpl(AppointmentRepository repository,
                                  CustomerService customerService,
                                  ClinicService clinicService,
                                  EmployeeService employeeService,
                                  DoctorAvailabilityService availability,
                                  RowLevelSecurity rowLevel,
                                  ExamRoomService examRooms,
                                  ApplicationEventPublisher events,
                                  Clock clock) {
        this.repository = repository;
        this.customerService = customerService;
        this.clinicService = clinicService;
        this.employeeService = employeeService;
        this.availability = availability;
        this.rowLevel = rowLevel;
        this.examRooms = examRooms;
        this.events = events;
        this.clock = clock;
    }

    @Override
    public Appointment getByCode(String rawCode) {
        String code = BookingCodes.normalize(rawCode);
        // Through the read scope: another clinic's appointment answers as not
        // found, never as forbidden.
        return repository.findOne(Specification.allOf(
                        rowLevel.scope(Appointment.class, Action.READ),
                        BaseEntitySpecifications.notDeleted(),
                        AppointmentSpecifications.byBookingCode(code)))
                .orElseThrow(() -> new ResourceNotFoundException("Appointment", code));
    }

    @Override
    public Page<Appointment> list(ListParams params) {
        return repository.findAll(
                rowLevel.scope(Appointment.class, Action.READ).and(AppointmentSpecifications.from(params)),
                params.pageable(SORTABLE, DEFAULT_SORT));
    }

    @Override
    public Page<Appointment> listForCustomer(Customer customer, ListParams params) {
        Specification<Appointment> spec = AppointmentSpecifications.from(params)
                .and(AppointmentSpecifications.ofAccount(customer.getId()));
        return repository.findAll(spec, params.pageable(SORTABLE, DEFAULT_SORT));
    }

    @Override
    public List<Appointment> getMany(List<Long> ids) {
        return repository.findAll(Specification.allOf(
                rowLevel.byIds(Appointment.class, Action.READ, ids), BaseEntitySpecifications.notDeleted()));
    }

    @Override
    public Appointment get(Long id) {
        return find(id, Action.READ);
    }

    @Override
    public Appointment getOwn(Customer customer, Long id) {
        return repository.findOwn(id, customer.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Appointment", id));
    }

    @Override
    public Integer queueAhead(Appointment appointment) {
        if (appointment.getStatus() != AppointmentStatus.CHECKED_IN
                || !appointment.getAppointmentDate().equals(today())) {
            return null;
        }
        Long doctorId = appointment.getEmployee().getId();
        // ponytail: reads the clinic's whole waiting list for the day (tens of
        // rows) and finds this one in it; a positional count query if it grows.
        List<Appointment> line = repository.findWaitingAt(appointment.getClinicId(), today()).stream()
                .filter(waiting -> doctorId.equals(waiting.getEmployee().getId()))
                .toList();
        for (int i = 0; i < line.size(); i++) {
            if (line.get(i).getId().equals(appointment.getId())) {
                return i;
            }
        }
        return null;
    }

    @Override
    @Transactional
    public Appointment bookFor(StaffBookingRequest request) {
        Customer customer = customerService.getForBooking(request.customerId());
        // Whether the desk may register at that doctor's clinic is the
        // "appointments" write policy's to say; see book.
        Appointment appointment = book(customer, request.booking(), true);
        appointment.setNote(blankToNull(request.note()));
        return appointment;
    }

    @Override
    @Transactional
    public Appointment bookOwn(Customer customer, BookingRequest request) {
        Customer patient = customer;
        if (request.patientId() != null) {
            try {
                patient = customerService.ownProfile(customer, request.patientId());
            } catch (ResourceNotFoundException e) {
                throw new FieldValidationException("patientId", "Pick one of your own profiles");
            }
        }
        if (!patient.isCompleteForVisit()) {
            throw new FieldValidationException("patientId",
                    "Fill in this profile's date of birth and gender before registering a visit");
        }
        return book(patient, request, false);
    }

    @Override
    @Transactional
    public Appointment update(Long id, AppointmentUpdateRequest request) {
        Appointment appointment = find(id, Action.WRITE);
        if (appointment.getStatus() != AppointmentStatus.SCHEDULED
                && appointment.getStatus() != AppointmentStatus.CHECKED_IN
                && appointment.getStatus() != AppointmentStatus.IN_EXAM) {
            throw new ConflictException("An appointment that is over can no longer be changed");
        }
        appointment.setReason(request.reason().trim());
        appointment.setNote(blankToNull(request.note()));
        rowLevel.checkWritable(appointment);
        return repository.persist(appointment);
    }

    @Override
    @Transactional
    public Appointment checkIn(Long id) {
        // USING through the write scope: an appointment the desk may see but
        // not change is a 404.
        Appointment appointment = find(id, Action.WRITE);
        if (appointment.getStatus() != AppointmentStatus.SCHEDULED) {
            throw new ConflictException("Only a registered appointment can be checked in");
        }
        if (!appointment.getAppointmentDate().equals(today())) {
            throw new ConflictException("An appointment can only be checked in on its own date");
        }

        appointment.checkIn(CurrentUser.id().orElse(null), OffsetDateTime.now(clock));
        rowLevel.checkWritable(appointment);
        customerService.activateIfNeutral(appointment.getCustomer(), PatientActivationSource.CHECK_IN);
        Appointment saved = repository.persist(appointment);
        // The doctor's room may be free and waiting for exactly this person.
        events.publishEvent(new ExamRoomEvents.QueueJoined(saved.getClinicId(), saved.getEmployee().getId()));
        return saved;
    }

    @Override
    @Transactional
    public Appointment complete(Long id) {
        Appointment appointment = find(id, Action.WRITE);
        if (!isUnderway(appointment)) {
            throw new ConflictException("Only a checked-in appointment can be completed");
        }
        // The room first: its lock is taken before this row is written, the
        // same order the dispatcher takes them in.
        examRooms.releaseFor(appointment);
        appointment.complete();
        rowLevel.checkWritable(appointment);
        return repository.persist(appointment);
    }

    @Override
    @Transactional
    public void completeForEncounter(Long appointmentId) {
        repository.findOne(Specification.allOf(
                        BaseEntitySpecifications.notDeleted(),
                        (root, query, cb) -> cb.equal(root.get("id"), appointmentId)))
                .filter(AppointmentServiceImpl::isUnderway)
                .ifPresent(appointment -> {
                    examRooms.releaseFor(appointment);
                    appointment.complete();
                    repository.persist(appointment);
                });
    }

    @Override
    @Transactional
    public Appointment markNoShow(Long id) {
        Appointment appointment = find(id, Action.WRITE);
        if (appointment.getStatus() != AppointmentStatus.SCHEDULED) {
            throw new ConflictException("Only a registered appointment can be marked as a no-show");
        }
        if (appointment.getAppointmentDate().isAfter(today())) {
            throw new ConflictException("That appointment's day has not come yet");
        }
        appointment.markNoShow();
        rowLevel.checkWritable(appointment);
        return repository.persist(appointment);
    }

    @Override
    @Transactional
    public Appointment cancel(Long id, String reason) {
        return cancel(find(id, Action.WRITE), reason.trim(), true);
    }

    @Override
    @Transactional
    public Appointment cancelOwn(Customer customer, Long id) {
        return cancel(getOwn(customer, id), null, false);
    }

    /** @param byStaff whether the desk cancels it, and so the write policy applies; see {@link #book}. */
    private Appointment cancel(Appointment appointment, String reason, boolean byStaff) {
        requireChangeable(appointment, "cancelled");
        appointment.cancel(CurrentUser.id().orElse(null), OffsetDateTime.now(clock), reason);
        if (byStaff) {
            rowLevel.checkWritable(appointment);
        }
        return repository.persist(appointment);
    }

    @Override
    @Transactional
    public Appointment reschedule(Long id, RescheduleRequest request) {
        return reschedule(find(id, Action.WRITE), request, true);
    }

    @Override
    @Transactional
    public Appointment rescheduleOwn(Customer customer, Long id, RescheduleRequest request) {
        return reschedule(getOwn(customer, id), request, false);
    }

    /** @param byStaff as in {@link #cancel(Appointment, String, boolean)}. */
    private Appointment reschedule(Appointment appointment, RescheduleRequest request, boolean byStaff) {
        requireChangeable(appointment, "rescheduled");

        Employee current = appointment.getEmployee();
        Long employeeId = request.employeeId() != null ? request.employeeId()
                : current != null ? current.getId() : null;
        if (employeeId == null) {
            throw new FieldValidationException("employeeId", "A doctor is required");
        }
        // Refused rather than given a second number in the day it already has one.
        if (current != null && employeeId.equals(current.getId())
                && request.appointmentDate().equals(appointment.getAppointmentDate())) {
            throw new FieldValidationException("appointmentDate",
                    "The appointment is already with that doctor on that day");
        }

        assign(appointment, appointment.getCustomer(), employeeId, request.appointmentDate());
        if (byStaff) {
            rowLevel.checkWritable(appointment);
        }
        return repository.persist(appointment);
    }

    /**
     * SRS BR-26: an appointment changes only while it is registered, and no
     * later than two hours before it is expected -- for the desk and the patient alike.
     */
    private void requireChangeable(Appointment appointment, String change) {
        if (appointment.getStatus() != AppointmentStatus.SCHEDULED) {
            throw new ConflictException("Only a registered appointment can be " + change);
        }
        LocalDateTime start = LocalDateTime.of(appointment.getAppointmentDate(), appointment.getStartTime());
        if (LocalDateTime.now(clock.withZone(clinicTimeZone())).isAfter(start.minusHours(2))) {
            throw new ConflictException(
                    "An appointment can only be " + change + " up to two hours before it is expected");
        }
    }

    /**
     * One row through the caller's scope for {@code action}, in the query
     * itself. Outside it is a 404, indistinguishable from a row that does not
     * exist -- deliberately, as in the framework's {@code TaskService}.
     */
    private Appointment find(Long id, Action action) {
        return repository.findOne(Specification.allOf(
                        rowLevel.byId(Appointment.class, action, id), BaseEntitySpecifications.notDeleted()))
                .orElseThrow(() -> new ResourceNotFoundException("Appointment", id));
    }

    /**
     * Registers a visit with a doctor for a day.
     *
     * @param byStaff whether the desk registers it, and so it has to fall
     *                inside the caller's row-level scope. A customer
     *                registering their own visit holds no scope and needs none.
     */
    private Appointment book(Customer customer, BookingRequest request, boolean byStaff) {
        Appointment appointment = new Appointment();
        appointment.setCustomer(customer);
        assign(appointment, customer, request.employeeId(), request.appointmentDate());
        appointment.setReason(request.reason().trim());
        appointment.setBookingCode(newBookingCode());
        Appointment saved = repository.persist(appointment);
        if (byStaff) {
            // WITH CHECK against the complete row, after the flush; a refusal
            // rolls the insert back.
            repository.flush();
            rowLevel.checkWritable(saved);
        }
        return saved;
    }

    /**
     * Puts the appointment in a doctor's day: the clinic, the next queue
     * number, and about when that number is called. What a registration and a
     * reschedule share.
     *
     * <p>The doctor's row is locked before the day is counted, so the last
     * turn of a day goes to exactly one of two people asking for it at once,
     * and no number is issued twice.
     */
    private void assign(Appointment appointment, Customer customer, Long employeeId, LocalDate date) {
        if (date.isBefore(clinicService.today())) {
            throw new FieldValidationException("appointmentDate", "The date cannot be in the past");
        }
        if (!clinicService.isWithinBookingWindow(date)) {
            throw new FieldValidationException("appointmentDate", "Registration is open up to %s".formatted(
                    clinicService.lastBookableDate()));
        }

        Employee doctor;
        try {
            doctor = employeeService.lockDoctor(employeeId);
        } catch (ResourceNotFoundException e) {
            throw new FieldValidationException("employeeId", "Pick one of the clinic's doctors");
        }
        if (doctor.getClinicId() == null) {
            throw new FieldValidationException("employeeId", "That doctor is not tied to a clinic");
        }

        List<Turn> turns = availability.turns(doctor, date);
        if (turns.isEmpty()) {
            throw new FieldValidationException("appointmentDate", "That doctor does not see patients on that day");
        }
        if (repository.existsByCustomerIdAndEmployeeIdAndAppointmentDateAndStatusAndDeletedAtIsNull(
                customer.getId(), doctor.getId(), date, AppointmentStatus.SCHEDULED)) {
            throw new ConflictException("There is already a visit registered with that doctor on that day");
        }
        int number = repository.maxQueueNumber(doctor.getId(), date) + 1;
        Turn turn = availability.turnFor(turns, date, number, repository.countWaiting(doctor.getId(), date))
                .orElseThrow(() -> new ConflictException("That doctor has no turn left on that day"));

        appointment.setEmployee(doctor);
        // SRS 3.18: a clinic that has stopped operating takes no new visits.
        if ("INACTIVE".equals(clinicService.get(doctor.getClinicId()).getOperatingStatus())) {
            throw new FieldValidationException("employeeId", "That doctor's clinic is not taking visits");
        }
        // Loaded, not the doctor's lazy reference: the response names the clinic after the transaction ends.
        appointment.setClinic(clinicService.get(doctor.getClinicId()));
        appointment.setAppointmentDate(date);
        appointment.setQueueNumber(number);
        appointment.setStartTime(turn.start());
        appointment.setEndTime(turn.end());
    }

    private String newBookingCode() {
        for (int attempt = 0; attempt < CODE_ATTEMPTS; attempt++) {
            String code = BookingCodes.generate();
            if (!repository.existsByBookingCode(code)) {
                return code;
            }
        }
        throw new IllegalStateException("Could not generate an unused booking code");
    }

    /** Checked in and not yet over: waiting for the doctor, or in the room with them. */
    private static boolean isUnderway(Appointment appointment) {
        return appointment.getStatus() == AppointmentStatus.CHECKED_IN
                || appointment.getStatus() == AppointmentStatus.IN_EXAM;
    }

    private LocalDate today() {
        return clinicService.today();
    }

    private ZoneId clinicTimeZone() {
        return clinicService.timeZone();
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
