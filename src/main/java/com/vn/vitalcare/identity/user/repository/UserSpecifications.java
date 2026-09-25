package com.vn.vitalcare.identity.user.repository;

import com.vn.vitalcare.identity.user.entity.User;
import com.vn.vitalcare.identity.user.entity.UserStatus;
import com.vn.vitalcare.share.web.ListParams;
import jakarta.persistence.criteria.JoinType;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/**
 * Translates the parsed query string into a JPA {@link Specification}.
 *
 * <p>Unknown fields are ignored rather than rejected, as everywhere else: a
 * new filter control can be added on the client before the backend knows
 * about it, and a 400 there would blank the whole table instead of merely
 * failing to narrow it.
 */
public final class UserSpecifications {

    private UserSpecifications() {
    }

    public static Specification<User> from(ListParams params) {
        List<Specification<User>> specs = new ArrayList<>();

        for (ListParams.Criterion criterion : params.filters()) {
            Specification<User> spec = switch (criterion.field()) {
                case "id" -> idSpec(criterion);
                case "username" -> textSpec("username", criterion);
                case "email" -> textSpec("email", criterion);
                case "fullName" -> textSpec("fullName", criterion);
                case "status" -> statusSpec(criterion);
                // The list view filters by role through the nested id the
                // response reports, so the field arrives as "roles.id".
                case "roles.id", "roleId" -> roleSpec(criterion);
                default -> null;
            };
            if (spec != null) {
                specs.add(spec);
            }
        }

        // The quick filter is one term matched across the three text columns,
        // because the provider has no way to express an OR across fields.
        params.search().ifPresent(term -> specs.add((root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("username")), ListParams.likePattern(term)),
                cb.like(cb.lower(root.get("email")), ListParams.likePattern(term)),
                cb.like(cb.lower(root.get("fullName")), ListParams.likePattern(term)))));

        List<Long> ids = params.ids();
        if (!ids.isEmpty()) {
            specs.add((root, query, cb) -> root.get("id").in(ids));
        }

        return Specification.allOf(specs);
    }

    private static Specification<User> idSpec(ListParams.Criterion criterion) {
        Long id = parseId(criterion.value());
        if (id == null) {
            // A non-numeric id can never match; say so explicitly rather than
            // letting the filter silently disappear.
            return (root, query, cb) -> cb.disjunction();
        }
        return switch (criterion.operator()) {
            case EQ, LIKE -> (root, query, cb) -> cb.equal(root.get("id"), id);
            case NE -> (root, query, cb) -> cb.notEqual(root.get("id"), id);
            case GTE -> (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("id"), id);
            case LTE -> (root, query, cb) -> cb.lessThanOrEqualTo(root.get("id"), id);
        };
    }

    private static Specification<User> textSpec(String field, ListParams.Criterion criterion) {
        String value = criterion.value();
        return switch (criterion.operator()) {
            case LIKE -> (root, query, cb) ->
                    cb.like(cb.lower(root.get(field)), ListParams.likePattern(value));
            case NE -> (root, query, cb) -> cb.notEqual(cb.lower(root.get(field)), value.toLowerCase());
            default -> (root, query, cb) -> cb.equal(cb.lower(root.get(field)), value.toLowerCase());
        };
    }

    private static Specification<User> statusSpec(ListParams.Criterion criterion) {
        UserStatus status;
        try {
            status = UserStatus.from(criterion.value());
        } catch (IllegalArgumentException e) {
            // An unknown status is a filter that can never match, not a request
            // worth rejecting: same reasoning as an unknown field.
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

    /**
     * Filters by an assigned role.
     *
     * <p>The join is inner and the query distinct: a user holding the role once
     * must appear once, and the many-to-many would otherwise multiply rows and
     * corrupt the page total.
     */
    private static Specification<User> roleSpec(ListParams.Criterion criterion) {
        Long roleId = parseId(criterion.value());
        if (roleId == null) {
            return (root, query, cb) -> cb.disjunction();
        }
        return (root, query, cb) -> {
            if (query != null) {
                query.distinct(true);
            }
            return cb.equal(root.join("roles", JoinType.INNER).get("id"), roleId);
        };
    }

    private static Long parseId(String value) {
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
