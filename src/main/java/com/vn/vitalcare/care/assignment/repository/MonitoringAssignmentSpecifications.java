package com.vn.vitalcare.care.assignment.repository;

import com.vn.vitalcare.entity.AssignmentStatus;
import com.vn.vitalcare.entity.MonitoringAssignment;
import com.vn.vitalcare.share.data.BaseEntitySpecifications;
import com.vn.vitalcare.share.web.ListParams;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/** The assignment list's filters; deleted rows are excluded. */
public final class MonitoringAssignmentSpecifications {

    private MonitoringAssignmentSpecifications() {
    }

    /** An assignment belongs to the clinic of the member of staff it puts on the care team. */
    public static Specification<MonitoringAssignment> ofClinic(UUID clinicId) {
        return (root, query, cb) -> cb.equal(root.get("employee").get("clinic").get("clinicId"), clinicId);
    }

    public static Specification<MonitoringAssignment> byId(Long id) {
        return (root, query, cb) -> cb.equal(root.get("id"), id);
    }

    public static Specification<MonitoringAssignment> from(ListParams params) {
        List<Specification<MonitoringAssignment>> specs = new ArrayList<>();
        specs.add(BaseEntitySpecifications.notDeleted());

        for (ListParams.Criterion criterion : params.filters()) {
            try {
                switch (criterion.field()) {
                    // active = the care teams as they stand; ended = the history.
                    case "status" -> {
                        // valueOf throws on anything else, which the catch below turns into no rows.
                        AssignmentStatus status = AssignmentStatus.valueOf(criterion.value().trim().toUpperCase());
                        specs.add((root, query, cb) -> cb.equal(root.get("status"), status));
                    }
                    case "customer.id", "customerId" -> {
                        Long customerId = Long.valueOf(criterion.value().trim());
                        specs.add((root, query, cb) -> cb.equal(root.get("customer").get("id"), customerId));
                    }
                    case "employee.id", "employeeId" -> {
                        Long employeeId = Long.valueOf(criterion.value().trim());
                        specs.add((root, query, cb) -> cb.equal(root.get("employee").get("id"), employeeId));
                    }
                    default -> {
                    }
                }
            } catch (IllegalArgumentException e) {
                specs.add((root, query, cb) -> cb.disjunction());
            }
        }

        params.search().ifPresent(term -> specs.add((root, query, cb) -> {
            Join<Object, Object> customer = root.join("customer", JoinType.LEFT);
            Join<Object, Object> employee = root.join("employee", JoinType.LEFT);
            String pattern = ListParams.likePattern(term);
            return cb.or(
                    cb.like(cb.lower(customer.get("user").get("fullName")), pattern),
                    cb.like(cb.lower(customer.get("customerCode")), pattern),
                    cb.like(cb.lower(employee.get("user").get("fullName")), pattern));
        }));

        List<Long> ids = params.ids();
        if (!ids.isEmpty()) {
            specs.add((root, query, cb) -> root.get("id").in(ids));
        }
        return Specification.allOf(specs);
    }
}
