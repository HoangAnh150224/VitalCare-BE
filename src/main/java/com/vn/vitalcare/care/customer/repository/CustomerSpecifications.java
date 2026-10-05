package com.vn.vitalcare.care.customer.repository;

import com.vn.vitalcare.entity.Customer;
import com.vn.vitalcare.entity.CustomerStatus;
import com.vn.vitalcare.share.data.BaseEntitySpecifications;
import com.vn.vitalcare.share.phone.PhoneNumbers;
import com.vn.vitalcare.share.web.ListParams;
import java.util.ArrayList;
import java.util.List;
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
