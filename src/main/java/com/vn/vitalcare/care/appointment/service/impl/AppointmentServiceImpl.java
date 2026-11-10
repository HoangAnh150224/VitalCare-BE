package com.vn.vitalcare.care.appointment.service.impl;

import com.vn.vitalcare.care.appointment.service.VisitEvents;
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
        if (appointment.getStatus() != AppointmentStatus.READY_FOR_EXAMINATION
                || !appointment.getAppointmentDate().equals(today())) {
            return null;
        }
        // The line it is in: the clinic's shared one for a visit with no doctor yet,
        // that doctor's for a referral (or a visit registered before the shared line).
        Long doctorId = doctorIdOf(appointment);
        // ponytail: reads the clinic's whole waiting list for the day (tens of
        // rows) and finds this one in it; a positional count query if it grows.
        List<Appointment> line = repository.findWaitingAt(appointment.getClinicId(), today()).stream()
                .filter(waiting -> java.util.Objects.equals(doctorId, doctorIdOf(waiting)))
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

    /**
     * The clinic a customer registers at: the one asked for, or the only one
     * taking visits. Several and none named is a question for the customer.
     * A client from before the shared line names a doctor instead: the visit
     * goes to that doctor's clinic, still in its shared line.
     */
    private java.util.UUID clinicFor(java.util.UUID asked, Long doctorId) {
        if (asked != null) {
            return asked;
        }
        if (doctorId != null) {
            return employeeService.doctor(doctorId).getClinicId();
        }
        List<java.util.UUID> open = clinicService.listAll().stream()
                .filter(c -> !"INACTIVE".equals(c.getOperatingStatus()))
                .map(com.vn.vitalcare.entity.Clinic::getClinicId)
                .toList();
        if (open.size() != 1) {
            throw new FieldValidationException("clinicId", "Pick a clinic");
        }
        return open.getFirst();
    }

    @Override
    @Transactional
    public Appointment update(Long id, AppointmentUpdateRequest request) {
        Appointment appointment = find(id, Action.WRITE);
        if (appointment.getStatus() != AppointmentStatus.BOOKED && !isUnderway(appointment)) {
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
        if (appointment.getStatus() != AppointmentStatus.BOOKED) {
            throw new ConflictException("Only a registered appointment can be checked in");
        }
        if (!appointment.getAppointmentDate().equals(today())) {
            throw new ConflictException("An appointment can only be checked in on its own date");
        }

        appointment.checkIn(CurrentUser.id().orElse(null), OffsetDateTime.now(clock));
        rowLevel.checkWritable(appointment);
        customerService.activateIfNeutral(appointment.getCustomer(), PatientActivationSource.CHECK_IN);
        Appointment saved = repository.persist(appointment);
        events.publishEvent(new VisitEvents.CheckedIn(saved.getId()));
        // The doctor's room may be free and waiting for exactly this person.
        events.publishEvent(new ExamRoomEvents.QueueJoined(saved.getClinicId(), doctorIdOf(saved)));
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
        Appointment saved = repository.persist(appointment);
        events.publishEvent(new VisitEvents.VisitEnded(saved.getId()));
        return saved;
    }

    @Override
    @Transactional
    public Appointment sendAway(Appointment appointment) {
        if (appointment.getStatus() != AppointmentStatus.IN_EXAM) {
            throw new ConflictException("Only a patient in the room can be sent out");
        }
        // The room first, as in complete: its lock before this row.
        examRooms.releaseFor(appointment);
        appointment.sendAway();
        Appointment saved = repository.persist(appointment);
        events.publishEvent(new ExamRoomEvents.BoardChanged(saved.getClinicId()));
        return saved;
    }

    /** The doctor a visit waits for: null while it waits in the clinic's shared line. */
    private static Long doctorIdOf(Appointment appointment) {
        return appointment.getEmployee() == null ? null : appointment.getEmployee().getId();
    }

    @Override
    @Transactional
    public Appointment referFrom(Appointment origin, Long doctorId, String reason) {
        if (origin.getStatus() != AppointmentStatus.IN_EXAM && origin.getStatus() != AppointmentStatus.AWAITING_RESULTS) {
            throw new ConflictException("Only a visit under way can be referred");
        }
        if (doctorId != null && origin.getEmployee() != null && doctorId.equals(origin.getEmployee().getId())) {
            throw new FieldValidationException("employeeId", "Pick another doctor");
        }
        boolean alreadySent = repository.findReferralsOf(origin.getId()).stream()
                .anyMatch(open -> open.getEmployee().getId().equals(doctorId) && isUnderwayOrRegistered(open));
        if (alreadySent) {
            throw new ConflictException("The patient is already referred to that doctor today");
        }
        Appointment referral = new Appointment();
        referral.setCustomer(origin.getCustomer());
        // Option A: the referral names its doctor and waits for that doctor's room only.
        assignReferral(referral, origin, doctorId);
        referral.setReason(reason.trim());
        referral.setBookingCode(newBookingCode());
        referral.setReferredFrom(origin);
        OffsetDateTime now = OffsetDateTime.now(clock);
        // The patient is here already: checked in, and first in line, as somebody who has waited once.
        referral.checkIn(CurrentUser.id().orElse(null), now);
        referral.putFirst(now);
        // The examination fee was paid on the visit that sent the patient here; this one carries none.
        referral.markPaid(now);
        Appointment saved = repository.persist(referral);
        if (origin.getStatus() == AppointmentStatus.IN_EXAM) {
            sendAway(origin);
        }
        events.publishEvent(new ExamRoomEvents.QueueJoined(saved.getClinicId(), doctorIdOf(saved)));
        return saved;
    }

    @Override
    @Transactional
    public void markExamPaid(Long appointmentId, OffsetDateTime when) {
        repository.lockById(appointmentId).ifPresent(appointment -> {
            boolean wasUnpaid = appointment.getPaidAt() == null;
            boolean wasCheckedIn = appointment.getStatus() == AppointmentStatus.CHECKED_IN;
            appointment.markPaid(when);
            repository.persist(appointment);
            if (wasUnpaid) {
                events.publishEvent(new VisitEvents.ExamPaid(appointment.getId()));
            }
            // Already at the desk: paying is what puts the patient in the line now.
            if (wasUnpaid && wasCheckedIn) {
                events.publishEvent(new ExamRoomEvents.QueueJoined(appointment.getClinicId(),
                        doctorIdOf(appointment)));
            }
        });
    }

    @Override
    @Transactional
    public Appointment walkIn(StaffBookingRequest request) {
        if (!request.booking().appointmentDate().equals(today())) {
            throw new FieldValidationException("appointmentDate", "A walk-in is registered for today");
        }
        Appointment booked = bookFor(request);
        return checkIn(booked.getId());
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
                    events.publishEvent(new VisitEvents.VisitEnded(appointment.getId()));
                });
    }

    @Override
    @Transactional
    public Appointment markNoShow(Long id) {
        Appointment appointment = find(id, Action.WRITE);
        // Also somebody checked in who left without paying: they never joined the line (BF-02).
        boolean leftUnpaid = appointment.getStatus() == AppointmentStatus.CHECKED_IN && appointment.getPaidAt() == null;
        if (appointment.getStatus() != AppointmentStatus.BOOKED && !leftUnpaid) {
            throw new ConflictException("Only a registered appointment can be marked as a no-show");
        }
        events.publishEvent(new VisitEvents.Cancelling(appointment.getId()));
        if (appointment.getAppointmentDate().isAfter(today())) {
            throw new ConflictException("That appointment's day has not come yet");
        }
        appointment.markNoShow();
        rowLevel.checkWritable(appointment);
        Appointment saved = repository.persist(appointment);
        events.publishEvent(new VisitEvents.Cancelled(saved.getId()));
        return saved;
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
        events.publishEvent(new VisitEvents.Cancelling(appointment.getId()));
        appointment.cancel(CurrentUser.id().orElse(null), OffsetDateTime.now(clock), reason);
        if (byStaff) {
            rowLevel.checkWritable(appointment);
        }
        Appointment saved = repository.persist(appointment);
        events.publishEvent(new VisitEvents.Cancelled(saved.getId()));
        return saved;
    }

    @Override
    public boolean hasOpenVisitAt(Customer customer, java.util.UUID clinicId, LocalDate date) {
        return repository.existsOpenAtClinic(customer.getId(), clinicId, date);
    }

    @Override
    public long noShowsOfAccount(Customer owner) {
        return repository.countNoShowsOfAccount(accountOf(owner).getId());
    }

    @Override
    @Transactional
    public Appointment choosePaymentAtDesk(Customer customer, Long id, boolean atDesk) {
        Appointment appointment = getOwn(customer, id);
        if (appointment.getPaidAt() != null) {
            throw new ConflictException("The examination fee is already paid");
        }
        if (appointment.getStatus() != AppointmentStatus.BOOKED
                && appointment.getStatus() != AppointmentStatus.CHECKED_IN) {
            throw new ConflictException("Only a visit still to come can change how it is paid");
        }
        if (atDesk && noShowsOfAccount(customer) >= NO_SHOWS_FOR_ONLINE_ONLY) {
            throw new ConflictException("After three no-shows the examination fee is paid online");
        }
        // Chosen once: choosing again keeps the first time and texts nobody twice.
        boolean newly = atDesk && appointment.getDeskPaymentChosenAt() == null;
        if (newly) {
            appointment.setDeskPaymentChosenAt(OffsetDateTime.now(clock));
        } else if (!atDesk) {
            appointment.setDeskPaymentChosenAt(null);
        }
        Appointment saved = repository.persist(appointment);
        if (newly) {
            events.publishEvent(new VisitEvents.DeskPaymentChosen(saved.getId()));
        }
        return saved;
    }

    @Override
    @Transactional
    public Appointment changeExaminee(Long id, Long customerId) {
        Appointment appointment = find(id, Action.WRITE);
        if (appointment.getStatus() != AppointmentStatus.BOOKED) {
            throw new ConflictException("Only a registered appointment can be given to someone else");
        }
        Customer other = customerService.getForBooking(customerId);
        if (other.getId().equals(appointment.getCustomer().getId())) {
            return appointment;
        }
        if (!accountOf(other).getId().equals(accountOf(appointment.getCustomer()).getId())) {
            throw new FieldValidationException("customerId", "Pick a profile of the same account");
        }
        if (!other.isCompleteForVisit()) {
            throw new FieldValidationException("customerId",
                    "Fill in this profile's date of birth and gender before registering a visit");
        }
        if (repository.existsScheduledAtClinic(other.getId(), appointment.getClinicId(),
                appointment.getAppointmentDate(), appointment.getId())) {
            throw new ConflictException("There is already a visit registered at that clinic on that day");
        }
        appointment.setCustomer(other);
        rowLevel.checkWritable(appointment);
        Appointment saved = repository.persist(appointment);
        // Its unpaid exam bill follows the visit to the new profile.
        events.publishEvent(new VisitEvents.Rescheduled(saved.getId()));
        return saved;
    }

    /** The account a profile belongs to: its own owner's profile, or its guardian's for a relative. */
    private static Customer accountOf(Customer profile) {
        return profile.getGuardian() != null ? profile.getGuardian() : profile;
    }

    @Override
    @Transactional
    public Appointment reschedule(Long id, RescheduleRequest request) {
        return reschedule(find(id, Action.WRITE), request, true);
    }

    @Override
    @Transactional
    public Appointment rescheduleOwn(Customer customer, Long id, RescheduleRequest request) {
        Appointment own = getOwn(customer, id);
        return reschedule(own, request, false);
    }


    /** @param byStaff as in {@link #cancel(Appointment, String, boolean)}. */
    private Appointment reschedule(Appointment appointment, RescheduleRequest request, boolean byStaff) {
        requireChangeable(appointment, "rescheduled");

        // Refused rather than given a second number in the day it already has one.
        if (request.appointmentDate().equals(appointment.getAppointmentDate())) {
            throw new FieldValidationException("appointmentDate", "The appointment is already on that day");
        }
        // Another day at the same clinic, in its shared line: no doctor until a room calls it in.
        assignToClinic(appointment, appointment.getCustomer(), appointment.getClinicId(), request.appointmentDate(),
                appointment.getId());
        if (byStaff) {
            rowLevel.checkWritable(appointment);
        }
        Appointment saved = repository.persist(appointment);
        events.publishEvent(new VisitEvents.Rescheduled(saved.getId()));
        return saved;
    }

    /**
     * SRS BR-26: an appointment changes only while it is registered, and no
     * later than two hours before it is expected -- for the desk and the patient alike.
     */
    private void requireChangeable(Appointment appointment, String change) {
        if (appointment.getStatus() != AppointmentStatus.BOOKED) {
            throw new ConflictException("Only a registered appointment can be " + change);
        }
        // A visit in the shared line has no expected time: it changes until it is checked in.
        if (appointment.getStartTime() == null) {
            if (appointment.getAppointmentDate().isBefore(today())) {
                throw new ConflictException("That appointment's day has passed");
            }
            return;
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
        assignToClinic(appointment, customer, clinicFor(request.clinicId(), request.employeeId()), request.appointmentDate(), null);
        appointment.setReason(request.reason().trim());
        appointment.setBookingCode(newBookingCode());
        Appointment saved = repository.persist(appointment);
        if (byStaff) {
            // WITH CHECK against the complete row, after the flush; a refusal
            // rolls the insert back.
            repository.flush();
            rowLevel.checkWritable(saved);
        }
        // Heard inside this transaction: the examination bill is issued with the registration.
        events.publishEvent(new VisitEvents.Registered(saved.getId()));
        return saved;
    }

    /**
     * Puts a visit in a clinic's day (BF-02, shared line): the clinic's next
     * number, no doctor and no expected time. Refused when no doctor of the
     * clinic sees patients that day, or the day's visits already fill every
     * doctor's turns; and a patient has one registered visit a day at a clinic.
     *
     * <p>Serialised per clinic and day by an advisory lock, so two people
     * registering at once never get the same number.
     */
    private void assignToClinic(Appointment appointment, Customer customer, java.util.UUID clinicId, LocalDate date,
                                Long excludeId) {
        if (date == null) {
            throw new FieldValidationException("appointmentDate", "A date is required");
        }
        if (date.isBefore(clinicService.today())) {
            throw new FieldValidationException("appointmentDate", "The date cannot be in the past");
        }
        if (!clinicService.isWithinBookingWindow(date)) {
            throw new FieldValidationException("appointmentDate", "Registration is open up to %s".formatted(
                    clinicService.lastBookableDate()));
        }
        com.vn.vitalcare.entity.Clinic clinic;
        try {
            clinic = clinicService.get(clinicId);
        } catch (ResourceNotFoundException e) {
            throw new FieldValidationException("clinicId", "Pick a clinic");
        }
        // SRS 3.18: a clinic that has stopped operating takes no new visits.
        if ("INACTIVE".equals(clinic.getOperatingStatus())) {
            throw new FieldValidationException("clinicId", "That clinic is not taking visits");
        }
        repository.lockClinicDay(clinicId + "/" + date);
        if (repository.existsScheduledAtClinic(customer.getId(), clinicId, date, excludeId == null ? -1L : excludeId)) {
            throw new ConflictException("There is already a visit registered at that clinic on that day");
        }
        DaySupply supply = supply(clinicId, date);
        if (!supply.stillOpen() || supply.capacity() == 0
                || repository.countActiveAtClinic(clinicId, date, excludeId == null ? -1L : excludeId)
                        >= supply.capacity()) {
            throw new FieldValidationException("appointmentDate", "No doctor has a turn left at that clinic on that day");
        }
        appointment.setEmployee(null);
        // Loaded, not a lazy reference: the response names the clinic after the transaction ends.
        appointment.setClinic(clinic);
        appointment.setAppointmentDate(date);
        appointment.setQueueNumber(repository.maxQueueNumberAtClinic(clinicId, date) + 1);
        appointment.setStartTime(null);
        appointment.setEndTime(null);
    }

    /**
     * A clinic's day: how many visits its doctors' turns hold (BR-01, shared by
     * online and walk-in visits), and whether a turn is still to come — on
     * any other day than today there always is. The last turn's end is the
     * day's last check-in (BR-10).
     */
    private record DaySupply(int capacity, boolean stillOpen) {
    }

    private DaySupply supply(java.util.UUID clinicId, LocalDate date) {
        int capacity = 0;
        boolean stillOpen = !date.equals(clinicService.today());
        java.time.LocalTime nowHere = java.time.LocalTime.now(clock.withZone(clinicTimeZone()));
        for (Employee doctor : employeeService.doctorsOf(clinicId)) {
            List<Turn> turns = availability.turns(doctor, date);
            capacity += turns.size();
            stillOpen = stillOpen || turns.stream().anyMatch(turn -> turn.end().isAfter(nowHere));
        }
        return new DaySupply(capacity, stillOpen);
    }

    @Override
    public List<OpenDay> openDays(java.util.UUID clinicId, LocalDate from, int days) {
        LocalDate start = from == null || from.isBefore(today()) ? today() : from;
        int wanted = Math.max(1, Math.min(days, 31));
        // ponytail: one count per day over a few weeks; a grouped count if the window grows.
        List<OpenDay> open = new java.util.ArrayList<>();
        for (LocalDate day = start; clinicService.isWithinBookingWindow(day) && open.size() < wanted;
             day = day.plusDays(1)) {
            DaySupply supply = supply(clinicId, day);
            if (!supply.stillOpen()) {
                continue;
            }
            int remaining = supply.capacity() - (int) repository.countActiveAtClinic(clinicId, day, -1L);
            if (remaining > 0) {
                open.add(new OpenDay(day, remaining));
            }
        }
        return open;
    }

    @Override
    @Transactional
    public int markNoShowsDue() {
        LocalDate today = today();
        java.util.Map<java.util.UUID, Boolean> openToday = new java.util.HashMap<>();
        int marked = 0;
        for (Long id : repository.findBookedUpTo(today)) {
            // The bill first, then the visit, as every cancellation takes them; the visit is
            // read under its lock, so one the desk checked in meanwhile is left alone.
            events.publishEvent(new VisitEvents.Cancelling(id));
            var locked = repository.lockById(id)
                    .filter(appointment -> appointment.getStatus() == AppointmentStatus.BOOKED);
            if (locked.isEmpty()) {
                continue;
            }
            Appointment due = locked.get();
            // Today only once the day's last turn is over; a day with no turns at all is left to
            // tomorrow's sweep rather than turning everybody away before they could come.
            if (due.getAppointmentDate().equals(today)
                    && openToday.computeIfAbsent(due.getClinicId(), clinic -> {
                        DaySupply supply = supply(clinic, today);
                        return supply.stillOpen() || supply.capacity() == 0;
                    })) {
                continue;
            }
            due.markNoShow();
            repository.persist(due);
            events.publishEvent(new VisitEvents.Cancelled(id));
            marked++;
        }
        return marked;
    }

    /**
     * A referral (option A): the named doctor, of the visit's own clinic and
     * seeing patients today; the clinic's next number, no expected time. It
     * waits for that doctor's room, ahead of the shared line.
     */
    private void assignReferral(Appointment referral, Appointment origin, Long doctorId) {
        Employee doctor;
        try {
            doctor = employeeService.lockDoctor(doctorId);
        } catch (ResourceNotFoundException e) {
            throw new FieldValidationException("employeeId", "Pick one of the clinic's doctors");
        }
        if (doctor.getClinicId() == null || !doctor.getClinicId().equals(origin.getClinicId())) {
            throw new FieldValidationException("employeeId", "Pick a doctor of the same clinic");
        }
        if (availability.turns(doctor, today()).isEmpty()) {
            throw new FieldValidationException("employeeId", "That doctor does not see patients on that day");
        }
        repository.lockClinicDay(origin.getClinicId() + "/" + today());
        referral.setEmployee(doctor);
        referral.setClinic(clinicService.get(origin.getClinicId()));
        referral.setAppointmentDate(today());
        referral.setQueueNumber(repository.maxQueueNumberAtClinic(origin.getClinicId(), today()) + 1);
        referral.setStartTime(null);
        referral.setEndTime(null);
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

    /** Checked in and not yet over: waiting to pay, waiting for the doctor, in the room with them, or out and coming back. */
    private static boolean isUnderway(Appointment appointment) {
        return appointment.getStatus() == AppointmentStatus.CHECKED_IN
                || appointment.getStatus() == AppointmentStatus.READY_FOR_EXAMINATION
                || appointment.getStatus() == AppointmentStatus.IN_EXAM
                || appointment.getStatus() == AppointmentStatus.AWAITING_RESULTS;
    }

    private static boolean isUnderwayOrRegistered(Appointment appointment) {
        return appointment.getStatus() == AppointmentStatus.BOOKED || isUnderway(appointment);
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
