package com.vn.vitalcare.care.staff.repository;

import com.vn.vitalcare.entity.Employee;
import com.vn.vitalcare.entity.EmployeeStatus;
import com.vn.vitalcare.entity.StaffType;
import com.vn.vitalcare.share.data.BaseEntitySpecifications;
import com.vn.vitalcare.share.phone.PhoneNumbers;
import com.vn.vitalcare.share.web.ListParams;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/**
 * Translates the parsed query string into a JPA {@link Specification}, as
 * {@code CustomerSpecifications} does. Deleted rows are always excluded.
 */
public final class EmployeeSpecifications {

    private EmployeeSpecifications() {
    }

    public static Specification<Employee> from(ListParams params) {
        List<Specification<Employee>> specs = new ArrayList<>();
        specs.add(BaseEntitySpecifications.notDeleted());

        for (ListParams.Criterion criterion : params.filters()) {
            Specification<Employee> spec = switch (criterion.field()) {
                case "status" -> statusSpec(criterion.value());
                case "staffType" -> staffTypeSpec(criterion.value());
                default -> null;
            };
            if (spec != null) {
                specs.add(spec);
            }
        }

        params.search().ifPresent(term -> specs.add((root, query, cb) -> {
            var user = root.join("user");
            return cb.or(
                    cb.like(cb.lower(user.get("fullName")), ListParams.likePattern(term)),
                    cb.like(user.get("phone"), ListParams.likePattern(PhoneNumbers.searchTail(term))),
                    cb.like(cb.lower(root.get("employeeCode")), ListParams.likePattern(term)),
                    cb.like(cb.lower(root.get("specialty")), ListParams.likePattern(term)));
        }));

        List<Long> ids = params.ids();
        if (!ids.isEmpty()) {
            specs.add((root, query, cb) -> root.get("id").in(ids));
        }
        return Specification.allOf(specs);
    }

    /** Only members of staff who can be put on a care team. */
    public static Specification<Employee> active() {
        return (root, query, cb) -> cb.equal(root.get("status"), EmployeeStatus.ACTIVE);
    }

    private static Specification<Employee> statusSpec(String raw) {
        try {
            EmployeeStatus status = EmployeeStatus.from(raw);
            return status == null ? null : (root, query, cb) -> cb.equal(root.get("status"), status);
        } catch (IllegalArgumentException e) {
            return (root, query, cb) -> cb.disjunction();
        }
    }

    private static Specification<Employee> staffTypeSpec(String raw) {
        try {
            StaffType type = StaffType.from(raw);
            return type == null ? null : (root, query, cb) -> cb.equal(root.get("staffType"), type);
        } catch (IllegalArgumentException e) {
            return (root, query, cb) -> cb.disjunction();
        }
    }
}
