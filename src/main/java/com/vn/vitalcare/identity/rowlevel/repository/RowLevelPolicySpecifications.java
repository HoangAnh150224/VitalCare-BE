package com.vn.vitalcare.identity.rowlevel.repository;

import com.vn.vitalcare.identity.rowlevel.entity.PolicyKind;
import com.vn.vitalcare.identity.rowlevel.entity.RowLevelPolicy;
import com.vn.vitalcare.share.web.ListParams;
import jakarta.persistence.criteria.JoinType;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;

/**
 * The parsed query string as a {@link Specification}, in the same shape as every
 * other resource's.
 *
 * <p>Unknown fields are ignored rather than rejected, as everywhere else: a new
 * filter control on the frontend degrades to "not narrowed" instead of blanking
 * the table with a 400.
 */
public final class RowLevelPolicySpecifications {

    private RowLevelPolicySpecifications() {
    }

    public static Specification<RowLevelPolicy> from(ListParams params) {
        List<Specification<RowLevelPolicy>> specs = new ArrayList<>();

        for (ListParams.Criterion criterion : params.filters()) {
            Specification<RowLevelPolicy> spec = switch (criterion.field()) {
                case "id" -> idSpec(criterion);
                case "resource" -> textSpec("resource", criterion);
                case "action" -> textSpec("action", criterion);
                case "name" -> textSpec("name", criterion);
                case "policyGroup" -> textSpec("policyGroup", criterion);
                case "kind" -> kindSpec(criterion);
                case "enabled" -> enabledSpec(criterion);
                case "role.id", "roleId" -> roleSpec(criterion);
                default -> null;
            };
            if (spec != null) {
                specs.add(spec);
            }
        }

        params.search().ifPresent(term -> specs.add((root, query, cb) -> {
            String pattern = ListParams.likePattern(term);
            return cb.or(
                    cb.like(cb.lower(root.get("name")), pattern),
                    cb.like(cb.lower(root.get("resource")), pattern),
                    cb.like(cb.lower(root.get("description")), pattern));
        }));

        List<Long> ids = params.ids();
        if (!ids.isEmpty()) {
            specs.add((root, query, cb) -> root.get("id").in(ids));
        }

        return Specification.allOf(specs);
    }

    private static Specification<RowLevelPolicy> idSpec(ListParams.Criterion criterion) {
        Long id = parseLong(criterion.value());
        if (id == null) {
            return never();
        }
        return criterion.operator() == ListParams.Operator.NE
                ? (root, query, cb) -> cb.notEqual(root.get("id"), id)
                : (root, query, cb) -> cb.equal(root.get("id"), id);
    }

    private static Specification<RowLevelPolicy> textSpec(String attribute, ListParams.Criterion criterion) {
        String value = criterion.value();
        return switch (criterion.operator()) {
            case LIKE -> (root, query, cb) ->
                    cb.like(cb.lower(root.get(attribute)), ListParams.likePattern(value));
            case NE -> (root, query, cb) -> cb.notEqual(root.get(attribute), value);
            default -> (root, query, cb) -> cb.equal(root.get(attribute), value);
        };
    }

    private static Specification<RowLevelPolicy> kindSpec(ListParams.Criterion criterion) {
        PolicyKind kind;
        try {
            kind = PolicyKind.valueOf(criterion.value().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            // A kind that does not exist is a valid question with an empty
            // answer, not a bad request.
            return never();
        }
        return criterion.operator() == ListParams.Operator.NE
                ? (root, query, cb) -> cb.notEqual(root.get("kind"), kind)
                : (root, query, cb) -> cb.equal(root.get("kind"), kind);
    }

    private static Specification<RowLevelPolicy> enabledSpec(ListParams.Criterion criterion) {
        boolean enabled = Boolean.parseBoolean(criterion.value().trim());
        return (root, query, cb) -> cb.equal(root.get("enabled"), enabled);
    }

    /**
     * Filters by role id, with an explicit left join.
     *
     * <p>{@code root.get("role").get("id")} would let JPA add an inner join,
     * which drops every {@code FILTER} — the rows with no role — before the
     * {@code _ne} branch can see them. It bites harder here than elsewhere: the
     * policies it would hide are the ones nobody can escape.
     */
    private static Specification<RowLevelPolicy> roleSpec(ListParams.Criterion criterion) {
        Long id = parseLong(criterion.value());
        if (id == null) {
            return never();
        }
        return (root, query, cb) -> {
            var join = root.join("role", JoinType.LEFT);
            return criterion.operator() == ListParams.Operator.NE
                    ? cb.or(cb.isNull(join.get("id")), cb.notEqual(join.get("id"), id))
                    : cb.equal(join.get("id"), id);
        };
    }

    private static Specification<RowLevelPolicy> never() {
        return (root, query, cb) -> cb.disjunction();
    }

    private static Long parseLong(String value) {
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
