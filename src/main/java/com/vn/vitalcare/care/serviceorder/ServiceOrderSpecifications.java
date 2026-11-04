package com.vn.vitalcare.care.serviceorder;

import com.vn.vitalcare.entity.ServiceOrder;
import com.vn.vitalcare.entity.ServiceOrderStatus;
import com.vn.vitalcare.share.data.BaseEntitySpecifications;
import com.vn.vitalcare.share.web.ListParams;
import jakarta.persistence.criteria.JoinType;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/** Translates the parsed query string into a JPA {@link Specification}; deleted rows are excluded. */
final class ServiceOrderSpecifications {

    private ServiceOrderSpecifications() {
    }

    static Specification<ServiceOrder> from(ListParams params) {
        List<Specification<ServiceOrder>> specs = new ArrayList<>();
        specs.add(BaseEntitySpecifications.notDeleted());

        for (ListParams.Criterion criterion : params.filters()) {
            try {
                switch (criterion.field()) {
                    case "status" -> {
                        ServiceOrderStatus status = ServiceOrderStatus.from(criterion.value());
                        if (status != null) {
                            specs.add((root, query, cb) -> criterion.operator() == ListParams.Operator.NE
                                    ? cb.notEqual(root.get("status"), status)
                                    : cb.equal(root.get("status"), status));
                        }
                    }
                    case "encounterId" -> {
                        Long encounterId = Long.valueOf(criterion.value().trim());
                        specs.add((root, query, cb) -> cb.equal(root.get("encounter").get("id"), encounterId));
                    }
                    default -> {
                    }
                }
            } catch (IllegalArgumentException e) {
                specs.add((root, query, cb) -> cb.disjunction());
            }
        }

        // The patient's own name, or their account's (somebody who registered themselves), or the service.
        params.search().ifPresent(term -> specs.add((root, query, cb) -> {
            var customer = root.join("customer");
            var account = customer.join("user", JoinType.LEFT);
            return cb.or(
                    cb.like(cb.lower(customer.get("fullName")), ListParams.likePattern(term)),
                    cb.like(cb.lower(account.get("fullName")), ListParams.likePattern(term)),
                    cb.like(cb.lower(root.get("service").get("name")), ListParams.likePattern(term)));
        }));

        List<Long> ids = params.ids();
        if (!ids.isEmpty()) {
            specs.add((root, query, cb) -> root.get("id").in(ids));
        }
        return Specification.allOf(specs);
    }
}
