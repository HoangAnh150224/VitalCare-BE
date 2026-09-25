package com.vn.vitalcare.identity.role.repository;

import com.vn.vitalcare.identity.role.entity.Role;
import com.vn.vitalcare.share.web.ListParams;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/** Translates the parsed query string into a JPA {@link Specification}. */
public final class RoleSpecifications {

    private RoleSpecifications() {
    }

    public static Specification<Role> from(ListParams params) {
        List<Specification<Role>> specs = new ArrayList<>();

        for (ListParams.Criterion criterion : params.filters()) {
            Specification<Role> spec = switch (criterion.field()) {
                case "id" -> idSpec(criterion);
                case "code" -> textSpec("code", criterion);
                case "name" -> textSpec("name", criterion);
                case "systemRole" -> booleanSpec(criterion);
                default -> null;
            };
            if (spec != null) {
                specs.add(spec);
            }
        }

        params.search().ifPresent(term -> specs.add((root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("code")), ListParams.likePattern(term)),
                cb.like(cb.lower(root.get("name")), ListParams.likePattern(term)),
                cb.like(cb.lower(cb.coalesce(root.get("description"), "")), ListParams.likePattern(term)))));

        List<Long> ids = params.ids();
        if (!ids.isEmpty()) {
            specs.add((root, query, cb) -> root.get("id").in(ids));
        }

        return Specification.allOf(specs);
    }

    private static Specification<Role> idSpec(ListParams.Criterion criterion) {
        Long id = parseId(criterion.value());
        if (id == null) {
            return (root, query, cb) -> cb.disjunction();
        }
        return switch (criterion.operator()) {
            case EQ, LIKE -> (root, query, cb) -> cb.equal(root.get("id"), id);
            case NE -> (root, query, cb) -> cb.notEqual(root.get("id"), id);
            case GTE -> (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("id"), id);
            case LTE -> (root, query, cb) -> cb.lessThanOrEqualTo(root.get("id"), id);
        };
    }

    private static Specification<Role> textSpec(String field, ListParams.Criterion criterion) {
        String value = criterion.value();
        return switch (criterion.operator()) {
            case LIKE -> (root, query, cb) ->
                    cb.like(cb.lower(root.get(field)), ListParams.likePattern(value));
            case NE -> (root, query, cb) -> cb.notEqual(root.get(field), value);
            default -> (root, query, cb) -> cb.equal(root.get(field), value);
        };
    }

    private static Specification<Role> booleanSpec(ListParams.Criterion criterion) {
        boolean value = Boolean.parseBoolean(criterion.value().trim());
        return switch (criterion.operator()) {
            case NE -> (root, query, cb) -> cb.notEqual(root.get("systemRole"), value);
            default -> (root, query, cb) -> cb.equal(root.get("systemRole"), value);
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
