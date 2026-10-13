package com.vn.vitalcare.care.appointment.service.impl;

import com.vn.vitalcare.care.appointment.dto.BookingRequest;
import com.vn.vitalcare.care.appointment.dto.RescheduleRequest;
import com.vn.vitalcare.care.appointment.dto.StaffBookingRequest;
import com.vn.vitalcare.care.appointment.repository.AppointmentRepository;
import com.vn.vitalcare.care.appointment.repository.AppointmentSpecifications;
import com.vn.vitalcare.care.appointment.service.AppointmentService;
import com.vn.vitalcare.care.appointment.service.BookingCodes;
import com.vn.vitalcare.care.clinic.service.ClinicScheduleService;
import com.vn.vitalcare.care.clinic.service.ClinicService;
import com.vn.vitalcare.care.clinic.service.Slot;
import com.vn.vitalcare.care.customer.service.CustomerService;
import com.vn.vitalcare.entity.Appointment;
import com.vn.vitalcare.entity.AppointmentStatus;
import com.vn.vitalcare.entity.Clinic;
import com.vn.vitalcare.entity.Customer;
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
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
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
    private static final Set<String> SORTABLE = Set.of("id", "appointmentDate", "startTime", "status", "createdAt");

    private static final Sort DEFAULT_SORT = Sort.by(
            Sort.Order.desc("appointmentDate"), Sort.Order.desc("startTime"), Sort.Order.desc("id"));

    /** Generating a code that is already taken is a one-in-billions event; a few retries cover it. */
    private static final int CODE_ATTEMPTS = 5;

    private final AppointmentRepository repository;
    private final CustomerService customerService;
    private final ClinicService clinicService;
    private final ClinicScheduleService schedule;
    // Which clinics' appointments the staff screens reach is a row-level
    // policy on "appointments". The customer's own paths (bookOwn, getOwn,
    // cancelOwn, listForCustomer) are pinned to the customer and do not go
    // through it: a customer holds no scope here, and the default is none.
    private final RowLevelSecurity rowLevel;
    private final Clock clock;

    public AppointmentServiceImpl(AppointmentRepository repository,
                                  CustomerService customerService,
                                  ClinicService clinicService,
                                  ClinicScheduleService schedule,
                                  RowLevelSecurity rowLevel,
                                  Clock clock) {
        this.repository = repository;
        this.customerService = customerService;
        this.clinicService = clinicService;
        this.schedule = schedule;
        this.rowLevel = rowLevel;
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
                .and(AppointmentSpecifications.ofCustomer(customer.getId()));
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
        return repository.findByIdAndCustomerIdAndDeletedAtIsNull(id, customer.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Appointment", id));
    }

    @Override
    @Transactional
    public Appointment bookFor(StaffBookingRequest request) {
        Customer customer = customerService.getForBooking(request.customerId());
        // Whether the desk may book at that clinic is the "appointments" write
        // policy's to say; see book.
        Appointment appointment = book(customer, request.booking(), true);
        appointment.setNote(blankToNull(request.note()));
        return appointment;
    }

    @Override
    @Transactional
    public Appointment bookOwn(Customer customer, BookingRequest request) {
        return book(customer, request, false);
    }

    @Override
    @Transactional
    public Appointment checkIn(Long id) {
        // USING through the write scope: an appointment the desk may see but
        // not change is a 404.
        Appointment appointment = find(id, Action.WRITE);
        if (appointment.getStatus() != AppointmentStatus.SCHEDULED) {
            throw new ConflictException("Only a scheduled appointment can be checked in");
        }
        if (!appointment.getAppointmentDate().equals(today())) {
            throw new ConflictException("An appointment can only be checked in on its own date");
        }

        appointment.checkIn(CurrentUser.id().orElse(null), OffsetDateTime.now(clock));
        rowLevel.checkWritable(appointment);
        customerService.activateIfNeutral(appointment.getCustomer(), PatientActivationSource.CHECK_IN);
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
        // Refused rather than counted around: the slot checks below would see
        // this appointment's own place as taken.
        if (request.appointmentDate().equals(appointment.getAppointmentDate())
                && request.startTime().equals(appointment.getStartTime())) {
            throw new FieldValidationException("startTime", "That is the time the appointment already has");
        }

        Clinic clinic = clinicService.getForUpdate(appointment.getClinicId());
        Slot slot = freeSlot(appointment.getCustomer(), clinic, request.appointmentDate(), request.startTime());
        appointment.setAppointmentDate(request.appointmentDate());
        appointment.setStartTime(slot.startTime());
        appointment.setEndTime(slot.endTime());
        if (byStaff) {
            rowLevel.checkWritable(appointment);
        }
        return repository.persist(appointment);
    }

    /**
     * SRS BR-26: an appointment changes only while it is scheduled, and no
     * later than two hours before it starts -- for the desk and the patient alike.
     */
    private void requireChangeable(Appointment appointment, String change) {
        if (appointment.getStatus() != AppointmentStatus.SCHEDULED) {
            throw new ConflictException("Only a scheduled appointment can be " + change);
        }
        LocalDateTime start = LocalDateTime.of(appointment.getAppointmentDate(), appointment.getStartTime());
        if (LocalDateTime.now(clock.withZone(clinicTimeZone())).isAfter(start.minusHours(2))) {
            throw new ConflictException(
                    "An appointment can only be " + change + " up to two hours before it starts");
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
     * Books one place in one of the clinic's slots.
     *
     * <p>The clinic row is locked before the slot is counted, so the last
     * place in a slot goes to exactly one of two people asking for it at once
     * — see {@code ClinicRepository.findForUpdate}. The end time is the slot's,
     * not the client's: a booking is a place in a slot, nothing finer.
     *
     * @param byStaff whether the booking is made at the desk, and so has to
     *                fall inside the caller's row-level scope. A customer
     *                booking their own visit holds no scope and needs none.
     */
    private Appointment book(Customer customer, BookingRequest request, boolean byStaff) {
        LocalDate date = request.appointmentDate();
        Clinic clinic = clinicService.getForUpdate(request.clinicId());
        Slot slot = freeSlot(customer, clinic, date, request.startTime());

        Appointment appointment = new Appointment();
        appointment.setCustomer(customer);
        appointment.setClinic(clinic);
        appointment.setAppointmentDate(date);
        appointment.setStartTime(slot.startTime());
        appointment.setEndTime(slot.endTime());
        appointment.setReason(blankToNull(request.reason()));
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
     * The slot of {@code clinic} starting at {@code start} on {@code date},
     * once it is known the customer can have a place in it. What a booking and
     * a reschedule share. The caller holds the clinic lock.
     */
    private Slot freeSlot(Customer customer, Clinic clinic, LocalDate date, LocalTime start) {
        if (date.isBefore(clinicService.today())) {
            throw new FieldValidationException("appointmentDate", "The date cannot be in the past");
        }
        if (!clinicService.isWithinBookingWindow(date)) {
            throw new FieldValidationException("appointmentDate", "Bookings are open up to %s".formatted(
                    clinicService.lastBookableDate()));
        }

        if (!schedule.isOpenOn(clinic, date)) {
            throw new FieldValidationException("appointmentDate", "The clinic is closed on that day");
        }
        Slot slot = schedule.slotStartingAt(clinic, date, start)
                .orElseThrow(() -> new FieldValidationException("startTime", "Pick one of the clinic's time slots"));

        LocalDateTime now = LocalDateTime.now(clock.withZone(clinicTimeZone()));
        if (!LocalDateTime.of(date, slot.startTime()).isAfter(now)) {
            throw new FieldValidationException("startTime", "That time has already passed today");
        }

        if (repository.existsByCustomerIdAndAppointmentDateAndStartTimeAndStatusAndDeletedAtIsNull(
                customer.getId(), date, slot.startTime(), AppointmentStatus.SCHEDULED)) {
            throw new ConflictException("There is already an appointment booked at that time");
        }
        if (schedule.bookedIn(clinic, date, slot.startTime()) >= slot.capacity()) {
            throw new ConflictException("That time slot is fully booked");
        }
        return slot;
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
