package com.vn.vitalcare.care.results;

import com.vn.vitalcare.care.appointment.service.AppointmentService;
import com.vn.vitalcare.care.customer.service.CustomerService;
import com.vn.vitalcare.entity.Appointment;
import com.vn.vitalcare.entity.AppointmentStatus;
import com.vn.vitalcare.entity.Customer;
import com.vn.vitalcare.entity.PatientFeedback;
import com.vn.vitalcare.identity.rowlevel.service.RowLevelSecurity;
import com.vn.vitalcare.share.data.BaseEntitySpecifications;
import com.vn.vitalcare.share.exception.ConflictException;
import com.vn.vitalcare.share.exception.FieldValidationException;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import com.vn.vitalcare.share.security.rowlevel.Action;
import com.vn.vitalcare.share.web.ListParams;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** FE-07 Patient Feedback: one per completed visit, the patient's to give and edit; the clinic reads it. */
@Service
@Transactional(readOnly = true)
public class Feedback {

    private static final Set<String> SORTABLE = Set.of("id", "rating", "createdAt");

    private final PatientFeedbackRepository feedback;
    private final AppointmentService appointments;
    private final CustomerService customers;
    private final RowLevelSecurity rowLevel;
    private final org.springframework.context.ApplicationEventPublisher events;

    public Feedback(PatientFeedbackRepository feedback, AppointmentService appointments, CustomerService customers,
                    RowLevelSecurity rowLevel, org.springframework.context.ApplicationEventPublisher events) {
        this.feedback = feedback;
        this.appointments = appointments;
        this.customers = customers;
        this.rowLevel = rowLevel;
        this.events = events;
    }

    public List<PatientFeedback> own() {
        return feedback.findForProfiles(profiles());
    }

    @Transactional
    public PatientFeedback give(Long appointmentId, Integer rating, String comment) {
        Customer me = customers.getForCurrentUser();
        Appointment visit = profiles().stream()
                .map(id -> {
                    try {
                        return appointments.getOwn(customers.ownProfile(me, id), appointmentId);
                    } catch (ResourceNotFoundException e) {
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Appointment", appointmentId));
        if (visit.getStatus() != AppointmentStatus.COMPLETED) {
            throw new ConflictException("Feedback is given on a completed visit");
        }
        if (feedback.existsForVisit(appointmentId)) {
            throw new ConflictException("You already gave feedback on this visit; edit it instead");
        }
        PatientFeedback f = new PatientFeedback();
        f.setAppointment(visit);
        f.setCustomer(visit.getCustomer());
        f.setClinicId(visit.getClinicId());
        apply(f, rating, comment);
        PatientFeedback saved = feedback.persist(f);
        feedback.flush();
        return feedback.findWithParties(saved.getId()).orElseThrow();
    }

    @Transactional
    public PatientFeedback edit(Long id, Integer rating, String comment) {
        PatientFeedback f = feedback.findForProfiles(profiles()).stream().filter(x -> x.getId().equals(id)).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Feedback", id));
        apply(f, rating, comment);
        return feedback.persist(f);
    }

    public Page<PatientFeedback> list(ListParams params) {
        List<Specification<PatientFeedback>> specs = new ArrayList<>();
        specs.add(rowLevel.scope(PatientFeedback.class, Action.READ));
        for (ListParams.Criterion c : params.filters()) {
            if ("rating".equals(c.field())) {
                Integer r = Integer.valueOf(c.value().trim());
                specs.add((root, q, cb) -> cb.equal(root.get("rating"), r));
            }
        }
        // Marks ignored as well as case (KAN-109): "tran thi bich" finds "Trần Thị Bích".
        params.search().ifPresent(term -> specs.add((root, q, cb) -> cb.or(
                cb.like(ListParams.folded(cb, root.join("customer").get("fullName")), ListParams.foldedPattern(term)),
                cb.like(ListParams.folded(cb, root.get("comment")), ListParams.foldedPattern(term)))));
        return feedback.findAll(Specification.allOf(specs),
                params.pageable(SORTABLE, Sort.by(Sort.Order.desc("createdAt"))));
    }

    public PatientFeedback get(Long id) {
        return feedback.findOne(rowLevel.byId(PatientFeedback.class, Action.READ, id))
                .orElseThrow(() -> new ResourceNotFoundException("Feedback", id));
    }

    @Transactional
    public PatientFeedback reply(Long id, String text) {
        if (text == null || text.isBlank() || text.trim().length() > 2000)
            throw new FieldValidationException("reply", "Vui lòng nhập phản hồi từ 1 đến 2000 ký tự.");
        PatientFeedback f = get(id);
        if (f.isDeleted()) throw new ConflictException("Góp ý đã xóa, chỉ có thể xem chi tiết.");
        f.setReply(text.trim());
        f.setRepliedAt(java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC));
        events.publishEvent(com.vn.vitalcare.share.audit.AuditEvent.of("feedback", id, "reply"));
        return feedback.persist(f);
    }

    @Transactional
    public void remove(Long id, String reason) {
        if (reason == null || reason.isBlank() || reason.trim().length() > 500)
            throw new FieldValidationException("reason", "Vui lòng nhập lý do xóa từ 1 đến 500 ký tự.");
        PatientFeedback f = get(id);
        if (f.isDeleted()) throw new ConflictException("Góp ý đã được xóa.");
        f.setRemovalReason(reason.trim());
        f.markDeleted(com.vn.vitalcare.share.security.CurrentUser.id().orElse(null), java.time.Instant.now());
        feedback.persist(f);
        events.publishEvent(new com.vn.vitalcare.share.audit.AuditEvent("feedback", id, "delete", null, java.util.Map.of("reason", reason.trim())));
    }

    private void apply(PatientFeedback f, Integer rating, String comment) {
        if (rating == null || rating < 1 || rating > 5) {
            throw new FieldValidationException("rating", "Rate from 1 to 5");
        }
        f.setRating(rating);
        String c = comment == null ? null : comment.trim();
        if (c != null && c.length() > 2000) {
            throw new FieldValidationException("comment", "At most 2000 characters");
        }
        f.setComment(c == null || c.isEmpty() ? null : c);
    }

    private List<Long> profiles() {
        return customers.profilesOf(customers.getForCurrentUser()).stream().map(Customer::getId).toList();
    }
}
