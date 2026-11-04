package com.vn.vitalcare.care.results;

import com.vn.vitalcare.care.appointment.service.AppointmentService;
import com.vn.vitalcare.care.customer.service.CustomerService;
import com.vn.vitalcare.care.notification.Notifier;
import com.vn.vitalcare.care.notification.VisitNotifications;
import com.vn.vitalcare.entity.Appointment;
import com.vn.vitalcare.entity.Customer;
import com.vn.vitalcare.entity.NotificationLevel;
import com.vn.vitalcare.entity.ResultReturn;
import com.vn.vitalcare.identity.rowlevel.service.RowLevelSecurity;
import com.vn.vitalcare.share.audit.AuditEvent;
import com.vn.vitalcare.share.data.BaseEntitySpecifications;
import com.vn.vitalcare.share.exception.ConflictException;
import com.vn.vitalcare.share.exception.FieldValidationException;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import com.vn.vitalcare.share.security.CurrentUser;
import com.vn.vitalcare.share.security.rowlevel.Action;
import com.vn.vitalcare.share.web.ListParams;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * BF-07 Result Return Schedule: the desk sets when a patient comes back for
 * their results (the patient is told), verifies them on arrival, and
 * reschedules a missed one. A return still scheduled the day after its date is
 * marked missed and the patient asked to book another time.
 */
@Service
@Transactional(readOnly = true)
public class ResultReturns {

    private static final Set<String> SORTABLE = Set.of("id", "scheduledAt", "status");
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("HH:mm 'ngày' dd/MM/yyyy");

    private final ResultReturnRepository returns;
    private final com.vn.vitalcare.care.encounter.repository.ClinicalEncounterRepository encounters;
    private final AppointmentService appointments;
    private final CustomerService customers;
    private final RowLevelSecurity rowLevel;
    private final Notifier notifier;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public ResultReturns(ResultReturnRepository returns,
                         com.vn.vitalcare.care.encounter.repository.ClinicalEncounterRepository encounters,
                         AppointmentService appointments, CustomerService customers,
                         RowLevelSecurity rowLevel, Notifier notifier, ApplicationEventPublisher events, Clock clock) {
        this.returns = returns;
        this.encounters = encounters;
        this.appointments = appointments;
        this.customers = customers;
        this.rowLevel = rowLevel;
        this.notifier = notifier;
        this.events = events;
        this.clock = clock;
    }

    public Page<ResultReturn> list(ListParams params) {
        List<Specification<ResultReturn>> specs = new ArrayList<>();
        specs.add(rowLevel.scope(ResultReturn.class, Action.READ));
        specs.add(BaseEntitySpecifications.notDeleted());
        for (ListParams.Criterion c : params.filters()) {
            String v = c.value() == null ? "" : c.value().trim();
            switch (c.field()) {
                case "status" -> specs.add((r, q, cb) -> cb.equal(r.get("status"), v.toUpperCase(Locale.ROOT)));
                case "appointmentId" -> specs.add((r, q, cb) -> cb.equal(r.get("appointment").get("id"), Long.valueOf(v)));
                // The day's list at the desk: date=YYYY-MM-DD.
                case "date" -> {
                    LocalDate day = LocalDate.parse(v.substring(0, 10));
                    OffsetDateTime from = day.atStartOfDay(ZONE).toOffsetDateTime();
                    OffsetDateTime to = day.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime();
                    specs.add((r, q, cb) -> cb.and(cb.greaterThanOrEqualTo(r.get("scheduledAt"), from),
                            cb.lessThan(r.get("scheduledAt"), to)));
                }
                default -> {
                }
            }
        }
        params.search().ifPresent(term -> specs.add((r, q, cb) ->
                cb.like(cb.lower(r.join("customer").get("fullName")), ListParams.likePattern(term))));
        return returns.findAll(Specification.allOf(specs), params.pageable(SORTABLE, Sort.by(Sort.Order.asc("scheduledAt"))));
    }

    public List<ResultReturn> own() {
        Customer me = customers.getForCurrentUser();
        return returns.findForProfiles(customers.profilesOf(me).stream().map(Customer::getId).toList());
    }

    @Transactional
    public ResultReturn schedule(Long appointmentId, OffsetDateTime at, String note) {
        return schedule(appointments.get(appointmentId), at, note);
    }

    /** From the visit's encounter: the doctor reaches the visit through it, not through the appointments list. */
    @Transactional
    public ResultReturn scheduleForEncounter(Long encounterId, OffsetDateTime at, String note) {
        com.vn.vitalcare.entity.ClinicalEncounter e = encounters.findOne(Specification.allOf(
                        rowLevel.byId(com.vn.vitalcare.entity.ClinicalEncounter.class, Action.READ, encounterId),
                        BaseEntitySpecifications.notDeleted()))
                .orElseThrow(() -> new ResourceNotFoundException("Clinical encounter", encounterId));
        if (e.getAppointment() == null) {
            throw new ConflictException("This encounter has no visit to return to");
        }
        return schedule(e.getAppointment(), at, note);
    }

    private ResultReturn schedule(Appointment visit, OffsetDateTime at, String note) {
        if (visit.getStatus() != com.vn.vitalcare.entity.AppointmentStatus.AWAITING_RESULTS
                && visit.getStatus() != com.vn.vitalcare.entity.AppointmentStatus.COMPLETED) {
            throw new ConflictException("Results are returned for a visit that was examined");
        }
        if (returns.hasOpen(visit.getId())) {
            throw new ConflictException("This visit already has a return scheduled; reschedule it instead");
        }
        future(at);
        ResultReturn r = new ResultReturn();
        r.setAppointment(visit);
        r.setCustomer(visit.getCustomer());
        r.setClinicId(visit.getClinicId());
        r.setScheduledAt(at);
        r.setNote(note == null || note.isBlank() ? null : note.trim());
        r.setStatus(ResultReturn.SCHEDULED);
        ResultReturn saved = returns.persist(r);
        returns.flush();
        rowLevel.checkWritable(saved);
        tell(saved, "Hẹn trả kết quả", "Mời bạn quay lại phòng khám nhận và nghe tư vấn kết quả lúc "
                + WHEN.format(at.atZoneSameInstant(ZONE)) + ".");
        events.publishEvent(new AuditEvent("result_returns", saved.getId(), "schedule", null, Map.of("at", at.toString())));
        return saved;
    }

    /** The patient is here: the desk checked who they are against the schedule. */
    @Transactional
    public ResultReturn arrive(Long id) {
        ResultReturn r = find(id, Action.WRITE);
        if (!ResultReturn.SCHEDULED.equals(r.getStatus()) && !ResultReturn.MISSED.equals(r.getStatus())) {
            throw new ConflictException("This return is already closed");
        }
        r.setStatus(ResultReturn.ARRIVED);
        r.setArrivedAt(OffsetDateTime.now(clock));
        r.setVerifiedById(CurrentUser.id().orElse(null));
        events.publishEvent(new AuditEvent("result_returns", id, "arrive", null, Map.of("status", "ARRIVED")));
        return returns.persist(r);
    }

    @Transactional
    public ResultReturn reschedule(Long id, OffsetDateTime at) {
        ResultReturn r = find(id, Action.WRITE);
        if (ResultReturn.ARRIVED.equals(r.getStatus()) || ResultReturn.CANCELLED.equals(r.getStatus())) {
            throw new ConflictException("This return is already closed");
        }
        future(at);
        r.setScheduledAt(at);
        r.setStatus(ResultReturn.SCHEDULED);
        r.setMissedAt(null);
        tell(r, "Đổi lịch trả kết quả", "Lịch nhận kết quả mới: " + WHEN.format(at.atZoneSameInstant(ZONE)) + ".");
        events.publishEvent(new AuditEvent("result_returns", id, "reschedule", null, Map.of("at", at.toString())));
        return returns.persist(r);
    }

    @Transactional
    public ResultReturn cancel(Long id) {
        ResultReturn r = find(id, Action.WRITE);
        if (ResultReturn.ARRIVED.equals(r.getStatus())) {
            throw new ConflictException("The patient already came");
        }
        r.setStatus(ResultReturn.CANCELLED);
        return returns.persist(r);
    }

    /** Hourly: a return still scheduled a day after its time was missed; the patient is asked to rebook. */
    @Scheduled(fixedDelayString = "${app.result-returns.sweep:1h}", initialDelayString = "${app.result-returns.sweep:1h}")
    @Transactional
    public void markMissed() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        for (ResultReturn r : returns.findOverdue(now.minusDays(1))) {
            r.setStatus(ResultReturn.MISSED);
            r.setMissedAt(now);
            tell(r, "Bạn đã lỡ hẹn trả kết quả", "Vui lòng liên hệ phòng khám hoặc nhắn tin để hẹn lại thời gian nhận kết quả.");
        }
    }

    private void tell(ResultReturn r, String title, String body) {
        notifier.notify(VisitNotifications.accountOf(r.getCustomer()), NotificationLevel.INFO, "appointment",
                title, body, "/my-appointments/show/" + r.getAppointment().getId());
    }

    private void future(OffsetDateTime at) {
        if (at == null || !at.isAfter(OffsetDateTime.now(clock))) {
            throw new FieldValidationException("scheduledAt", "Choose a time in the future");
        }
    }

    private ResultReturn find(Long id, Action action) {
        return returns.findOne(Specification.allOf(rowLevel.byId(ResultReturn.class, action, id),
                        BaseEntitySpecifications.notDeleted()))
                .orElseThrow(() -> new ResourceNotFoundException("Result return", id));
    }
}
