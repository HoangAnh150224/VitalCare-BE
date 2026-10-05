package com.vn.vitalcare.care.appointment.repository;

import com.vn.vitalcare.entity.Appointment;
import com.vn.vitalcare.entity.AppointmentStatus;
import com.vn.vitalcare.share.data.BaseEntitySpecifications;
import com.vn.vitalcare.share.phone.PhoneNumbers;
import com.vn.vitalcare.share.web.ListParams;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/**
 * Translates the parsed query string into a JPA {@link Specification}.
 *
 * <p>Unknown fields are ignored and unparseable values match nothing, as in
 * {@code UserSpecifications}. Deleted appointments are always excluded.
 */
public final class AppointmentSpecifications {

    private AppointmentSpecifications() {
    }

    public static Specification<Appointment> from(ListParams params) {
        List<Specification<Appointment>> specs = new ArrayList<>();
        specs.add(BaseEntitySpecifications.notDeleted());

        for (ListParams.Criterion criterion : params.filters()) {
            Specification<Appointment> spec = switch (criterion.field()) {
                case "status" -> statusSpec(criterion);
                case "appointmentDate" -> dateSpec(criterion);
                // Filtered through the nested id the response reports.
                case "customer.id", "customerId" -> customerSpec(criterion);
                case "clinic.id", "clinicId" -> clinicSpec(criterion);
                default -> null;
            };
            if (spec != null) {
                specs.add(spec);
            }
        }

        params.search().ifPresent(term -> specs.add((root, query, cb) -> {
            var customer = root.join("customer");
            var user = customer.join("user");
            return cb.or(
                    cb.like(cb.lower(user.get("fullName")), ListParams.likePattern(term)),
                    cb.like(user.get("phone"), ListParams.likePattern(PhoneNumbers.searchTail(term))),
                    cb.like(cb.lower(customer.get("customerCode")), ListParams.likePattern(term)));
        }));

        List<Long> ids = params.ids();
        if (!ids.isEmpty()) {
            specs.add((root, query, cb) -> root.get("id").in(ids));
        }

        return Specification.allOf(specs);
    }

    /** Pins a query to one customer's appointments — the customer-facing endpoints. */
    public static Specification<Appointment> ofCustomer(Long customerId) {
        return (root, query, cb) -> cb.equal(root.get("customer").get("id"), customerId);
    }

    private static Specification<Appointment> statusSpec(ListParams.Criterion criterion) {
        AppointmentStatus status;
        try {
            status = AppointmentStatus.from(criterion.value());
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

    private static Specification<Appointment> dateSpec(ListParams.Criterion criterion) {
        LocalDate date;
        try {
            date = LocalDate.parse(criterion.value());
        } catch (DateTimeParseException e) {
            return (root, query, cb) -> cb.disjunction();
        }
        return switch (criterion.operator()) {
            case GTE -> (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("appointmentDate"), date);
            case LTE -> (root, query, cb) -> cb.lessThanOrEqualTo(root.get("appointmentDate"), date);
            case NE -> (root, query, cb) -> cb.notEqual(root.get("appointmentDate"), date);
            default -> (root, query, cb) -> cb.equal(root.get("appointmentDate"), date);
        };
    }

    private static Specification<Appointment> customerSpec(ListParams.Criterion criterion) {
        Long id;
        try {
            id = Long.valueOf(criterion.value().trim());
        } catch (NumberFormatException e) {
            return (root, query, cb) -> cb.disjunction();
        }
        return ofCustomer(id);
    }

    private static Specification<Appointment> clinicSpec(ListParams.Criterion criterion) {
        UUID id;
        try {
            id = UUID.fromString(criterion.value().trim());
        } catch (IllegalArgumentException e) {
            return (root, query, cb) -> cb.disjunction();
        }
        return (root, query, cb) -> cb.equal(root.get("clinic").get("clinicId"), id);
    }
}
