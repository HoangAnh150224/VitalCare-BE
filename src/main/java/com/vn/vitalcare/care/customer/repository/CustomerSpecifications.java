package com.vn.vitalcare.care.customer.repository;

import com.vn.vitalcare.entity.Appointment;
import com.vn.vitalcare.entity.Customer;
import com.vn.vitalcare.entity.CustomerStatus;
import com.vn.vitalcare.entity.MonitoringAssignment;
import com.vn.vitalcare.share.data.BaseEntitySpecifications;
import com.vn.vitalcare.share.phone.PhoneNumbers;
import com.vn.vitalcare.share.web.ListParams;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/**
 * Translates the parsed query string into a JPA {@link Specification}.
 *
 * <p>Unknown fields are ignored, as in {@code UserSpecifications}. Deleted
 * customers are always excluded — see {@link BaseEntitySpecifications}.
 */
public final class CustomerSpecifications {

    private CustomerSpecifications() {
    }

    public static Specification<Customer> from(ListParams params) {
        List<Specification<Customer>> specs = new ArrayList<>();
        specs.add(BaseEntitySpecifications.notDeleted());

        for (ListParams.Criterion criterion : params.filters()) {
            Specification<Customer> spec = switch (criterion.field()) {
                case "status" -> statusSpec(criterion);
                case "customerCode" -> customerCodeSpec(criterion);
                case "clinicId" -> clinicSpec(criterion);
                default -> null;
            };
            if (spec != null) {
                specs.add(spec);
            }
        }

        // One term across the name, the number and the code -- the three
        // things a receptionist has in front of them when somebody walks in.
        params.search().ifPresent(term -> specs.add((root, query, cb) -> {
            var user = root.join("user");
            return cb.or(
                    cb.like(cb.lower(user.get("fullName")), ListParams.likePattern(term)),
                    cb.like(user.get("phone"), ListParams.likePattern(PhoneNumbers.searchTail(term))),
                    cb.like(cb.lower(root.get("customerCode")), ListParams.likePattern(term)));
        }));

        List<Long> ids = params.ids();
        if (!ids.isEmpty()) {
            specs.add((root, query, cb) -> root.get("id").in(ids));
        }

        return Specification.allOf(specs);
    }

    /**
     * The customers a clinic serves: booked there at least once, or followed
     * by somebody who works there.
     */
    public static Specification<Customer> ofClinic(UUID clinicId) {
        return (root, query, cb) -> {
            Subquery<Long> booked = query.subquery(Long.class);
            Root<Appointment> appointment = booked.from(Appointment.class);
            booked.select(appointment.get("id")).where(
                    cb.equal(appointment.get("customer"), root),
                    cb.equal(appointment.get("clinic").get("clinicId"), clinicId),
                    cb.isNull(appointment.get("deletedAt")));

            Subquery<Long> followed = query.subquery(Long.class);
            Root<MonitoringAssignment> assignment = followed.from(MonitoringAssignment.class);
            followed.select(assignment.get("id")).where(
                    cb.equal(assignment.get("customer"), root),
                    cb.equal(assignment.get("employee").get("clinic").get("clinicId"), clinicId),
                    cb.isNull(assignment.get("unassignedAt")),
                    cb.isNull(assignment.get("deletedAt")));

            return cb.or(cb.exists(booked), cb.exists(followed));
        };
    }

    /**
     * What the front desk of one clinic may see: the customers it serves, and
     * everybody who has never booked anywhere — somebody who just registered
     * belongs to no clinic yet, and the desk has to find them to book for them.
     */
    public static Specification<Customer> visibleAtClinic(UUID clinicId) {
        Specification<Customer> unbooked = (root, query, cb) -> {
            Subquery<Long> any = query.subquery(Long.class);
            Root<Appointment> appointment = any.from(Appointment.class);
            any.select(appointment.get("id")).where(
                    cb.equal(appointment.get("customer"), root),
                    cb.isNull(appointment.get("deletedAt")));
            return cb.not(cb.exists(any));
        };
        return ofClinic(clinicId).or(unbooked);
    }

    /**
     * The one customer a term names exactly: their whole phone number, or
     * their customer code. What a receptionist has when somebody from another
     * clinic walks in — knowing it is what identifies them, so it reaches past
     * the clinic's own list.
     */
    public static Specification<Customer> exactly(String term) {
        String code = term.trim();
        var phone = PhoneNumbers.normalize(term);
        return (root, query, cb) -> {
            var byCode = cb.equal(cb.lower(root.get("customerCode")), code.toLowerCase(Locale.ROOT));
            return phone
                    .map(normalized -> cb.or(byCode, cb.equal(root.join("user").get("phone"), normalized)))
                    .orElse(byCode);
        };
    }

    private static Specification<Customer> clinicSpec(ListParams.Criterion criterion) {
        try {
            return ofClinic(UUID.fromString(criterion.value().trim()));
        } catch (IllegalArgumentException e) {
            return (root, query, cb) -> cb.disjunction();
        }
    }

    private static Specification<Customer> statusSpec(ListParams.Criterion criterion) {
        CustomerStatus status;
        try {
            status = CustomerStatus.from(criterion.value());
        } catch (IllegalArgumentException e) {
            return (root, query, cb) -> cb.disjunction();
        }
        if (status == null) {
            return null;
        }
        return switch (criterion.operator()) {
            case NE -> (root, query, cb) -> cb.notEqual(root.get("status"), status);
            default -> (root, query, cb) -> cb.equal(root.get("status"), status);
        };
    }

    private static Specification<Customer> customerCodeSpec(ListParams.Criterion criterion) {
        String value = criterion.value();
        return switch (criterion.operator()) {
            case LIKE -> (root, query, cb) ->
                    cb.like(cb.lower(root.get("customerCode")), ListParams.likePattern(value));
            default -> (root, query, cb) -> cb.equal(cb.lower(root.get("customerCode")), value.toLowerCase());
        };
    }
}
