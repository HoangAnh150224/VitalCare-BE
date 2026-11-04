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

    public Feedback(PatientFeedbackRepository feedback, AppointmentService appointments, CustomerService customers,
                    RowLevelSecurity rowLevel) {
        this.feedback = feedback;
        this.appointments = appointments;
        this.customers = customers;
        this.rowLevel = rowLevel;
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
        specs.add(BaseEntitySpecifications.notDeleted());
        for (ListParams.Criterion c : params.filters()) {
            if ("rating".equals(c.field())) {
                Integer r = Integer.valueOf(c.value().trim());
                specs.add((root, q, cb) -> cb.equal(root.get("rating"), r));
            }
        }
        params.search().ifPresent(term -> specs.add((root, q, cb) -> cb.or(
                cb.like(cb.lower(root.join("customer").get("fullName")), ListParams.likePattern(term)),
                cb.like(cb.lower(root.get("comment")), ListParams.likePattern(term)))));
        return feedback.findAll(Specification.allOf(specs),
                params.pageable(SORTABLE, Sort.by(Sort.Order.desc("createdAt"))));
    }

    public PatientFeedback get(Long id) {
        return feedback.findOne(Specification.allOf(rowLevel.byId(PatientFeedback.class, Action.READ, id),
                        BaseEntitySpecifications.notDeleted()))
                .orElseThrow(() -> new ResourceNotFoundException("Feedback", id));
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
