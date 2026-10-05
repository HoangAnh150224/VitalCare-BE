package com.vn.vitalcare.care.appointment.service;

import com.vn.vitalcare.care.appointment.dto.BookingRequest;
import com.vn.vitalcare.care.appointment.dto.StaffBookingRequest;
import com.vn.vitalcare.care.appointment.repository.AppointmentRepository;
import com.vn.vitalcare.care.appointment.repository.AppointmentSpecifications;
import com.vn.vitalcare.care.clinic.service.ClinicScheduleService;
import com.vn.vitalcare.care.clinic.service.ClinicService;
import com.vn.vitalcare.care.clinic.service.Slot;
import com.vn.vitalcare.care.customer.service.CustomerService;
import com.vn.vitalcare.entity.Appointment;
import com.vn.vitalcare.entity.AppointmentStatus;
import com.vn.vitalcare.entity.Clinic;
import com.vn.vitalcare.entity.Customer;
import com.vn.vitalcare.entity.PatientActivationSource;
import com.vn.vitalcare.share.data.BaseEntitySpecifications;
import com.vn.vitalcare.share.exception.ConflictException;
import com.vn.vitalcare.share.exception.FieldValidationException;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import com.vn.vitalcare.share.security.CurrentUser;
import com.vn.vitalcare.share.web.ListParams;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The appointment book: booking, cancelling, and the check-in that can turn a
 * neutral customer into a patient.
 */
@Service
@Transactional(readOnly = true)
public class AppointmentService {

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
    private final Clock clock;

    public AppointmentService(AppointmentRepository repository,
                              CustomerService customerService,
                              ClinicService clinicService,
                              ClinicScheduleService schedule,
                              Clock clock) {
        this.repository = repository;
        this.customerService = customerService;
        this.clinicService = clinicService;
        this.schedule = schedule;
        this.clock = clock;
    }

    /**
     * The appointment a slip's code names, as typed or scanned at the front
     * desk — {@code k7m2-9qxa} finds {@code K7M29QXA}.
     */
    public Appointment getByCode(String rawCode) {
        String code = BookingCodes.normalize(rawCode);
        return repository.findByBookingCodeAndDeletedAtIsNull(code)
                .orElseThrow(() -> new ResourceNotFoundException("Appointment", code));
    }

    public Page<Appointment> list(ListParams params) {
        return repository.findAll(AppointmentSpecifications.from(params), params.pageable(SORTABLE, DEFAULT_SORT));
    }

    /** One customer's appointments; the filter cannot be widened by anything in {@code params}. */
    public Page<Appointment> listForCustomer(Customer customer, ListParams params) {
        Specification<Appointment> spec = AppointmentSpecifications.from(params)
                .and(AppointmentSpecifications.ofCustomer(customer.getId()));
        return repository.findAll(spec, params.pageable(SORTABLE, DEFAULT_SORT));
    }

    public List<Appointment> getMany(List<Long> ids) {
        Specification<Appointment> byIds = (root, query, cb) -> root.get("id").in(ids);
        return repository.findAll(Specification.allOf(BaseEntitySpecifications.notDeleted(), byIds));
    }

    public Appointment get(Long id) {
        return repository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Appointment", id));
    }

    /**
     * One of the customer's own appointments.
     *
     * <p>Somebody else's id answers exactly as a missing one does — a 404, not
     * a 403 — so the endpoint cannot be used to learn which ids exist.
     */
    public Appointment getOwn(Customer customer, Long id) {
        return repository.findByIdAndCustomerIdAndDeletedAtIsNull(id, customer.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Appointment", id));
    }

    /** The front desk booking for a customer. Booking never activates anybody. */
    @Transactional
    public Appointment bookFor(StaffBookingRequest request) {
        Customer customer = customerService.get(request.customerId());
        Appointment appointment = book(customer, request.booking());
        appointment.setNote(blankToNull(request.note()));
        return appointment;
    }

    /** A customer booking for themselves. Booking never activates anybody. */
    @Transactional
    public Appointment bookOwn(Customer customer, BookingRequest request) {
        return book(customer, request);
    }

    /**
     * Records the customer's arrival, and makes a neutral customer a patient.
     *
     * <p>Only on the appointment's own date, as the clinic counts days: a
     * check-in recorded on another day is either a mistake or a way to
     * activate somebody without them having turned up, and activation is the
     * one consequence here that matters. A different day needs a manual
     * activation, which says plainly that it was one.
     *
     * <p>The arrival and the activation are one transaction: an arrival
     * recorded without the activation it should have caused would leave the
     * customer neutral with nothing left to trigger it.
     */
    @Transactional
    public Appointment checkIn(Long id) {
        Appointment appointment = get(id);
        if (appointment.getStatus() != AppointmentStatus.SCHEDULED) {
            throw new ConflictException("Only a scheduled appointment can be checked in");
        }
        if (!appointment.getAppointmentDate().equals(today())) {
            throw new ConflictException("An appointment can only be checked in on its own date");
        }

        appointment.checkIn(CurrentUser.id().orElse(null), OffsetDateTime.now(clock));
        customerService.activateIfNeutral(appointment.getCustomer(), PatientActivationSource.CHECK_IN);
        return repository.save(appointment);
    }

    @Transactional
    public Appointment cancel(Long id) {
        return cancel(get(id));
    }

    @Transactional
    public Appointment cancelOwn(Customer customer, Long id) {
        return cancel(getOwn(customer, id));
    }

    private Appointment cancel(Appointment appointment) {
        if (appointment.getStatus() != AppointmentStatus.SCHEDULED) {
            throw new ConflictException("Only a scheduled appointment can be cancelled");
        }
        appointment.cancel(CurrentUser.id().orElse(null), OffsetDateTime.now(clock));
        return repository.save(appointment);
    }

    /**
     * Books one place in one of the clinic's slots.
     *
     * <p>The clinic row is locked before the slot is counted, so the last
     * place in a slot goes to exactly one of two people asking for it at once
     * — see {@code ClinicRepository.findForUpdate}. The end time is the slot's,
     * not the client's: a booking is a place in a slot, nothing finer.
     */
    private Appointment book(Customer customer, BookingRequest request) {
        LocalDate date = request.appointmentDate();
        if (date.isBefore(clinicService.today())) {
            throw new FieldValidationException("appointmentDate", "The date cannot be in the past");
        }
        if (!clinicService.isWithinBookingWindow(date)) {
            throw new FieldValidationException("appointmentDate", "Bookings are open up to %s".formatted(
                    clinicService.lastBookableDate()));
        }

        Clinic clinic = clinicService.getForUpdate(request.clinicId());
        if (!schedule.isOpenOn(clinic, date)) {
            throw new FieldValidationException("appointmentDate", "The clinic is closed on that day");
        }
        Slot slot = schedule.slotStartingAt(clinic, date, request.startTime())
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

        Appointment appointment = new Appointment();
        appointment.setCustomer(customer);
        appointment.setClinic(clinic);
        appointment.setAppointmentDate(date);
        appointment.setStartTime(slot.startTime());
        appointment.setEndTime(slot.endTime());
        appointment.setReason(blankToNull(request.reason()));
        appointment.setBookingCode(newBookingCode());
        return repository.save(appointment);
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
