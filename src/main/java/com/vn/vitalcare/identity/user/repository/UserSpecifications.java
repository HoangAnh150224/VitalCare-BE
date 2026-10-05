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
                case "phone" -> phoneSpec(criterion);
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
        //
        // The phone leg searches the significant digits rather than the term as
        // typed, for the reason given on phoneSearchTail.
        //
        // email is nullable, and lower(NULL) LIKE '%x%' is NULL rather than
        // false — an account without one simply contributes nothing to this
        // OR, which is what it should do.
        params.search().ifPresent(term -> specs.add((root, query, cb) -> cb.or(
                cb.like(root.get("phone"), ListParams.likePattern(phoneSearchTail(term))),
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

    /**
     * Filters by phone number, matched the way somebody would write one down.
     *
     * <p>Numbers are stored normalised, as {@code +84} and nine digits, so a
     * term typed any other way is not a substring of the stored value at all:
     * {@code 0901234567} and {@code +84901234567} share no {@code 0901}. A
     * plain text filter here answers "no such account" for an account that
     * exists, which is the worst answer available.
     *
     * <p>Exact comparison needs the whole number, so a partial term can only
     * ever be a {@code contains}. Asking whether a partial term <em>equals</em>
     * a number matches nothing; asking whether it differs matches everything.
     * ({@link #idSpec} answers "nothing" to both, which is wrong for the
     * second — left alone here as it is not this change's to fix.)
     */
    private static Specification<User> phoneSpec(ListParams.Criterion criterion) {
        String tail = phoneSearchTail(criterion.value());
        // lower() is pointless on digits, so the column is compared as stored.
        return switch (criterion.operator()) {
            case LIKE -> (root, query, cb) ->
                    cb.like(root.get("phone"), ListParams.likePattern(tail));
            case NE -> tail.length() == 9
                    ? (root, query, cb) -> cb.notEqual(root.get("phone"), "+84" + tail)
                    : (root, query, cb) -> cb.conjunction();
            default -> tail.length() == 9
                    ? (root, query, cb) -> cb.equal(root.get("phone"), "+84" + tail)
                    : (root, query, cb) -> cb.disjunction();
        };
    }

    /**
     * A search term reduced to the digits that appear in a stored number.
     *
     * <p>Drops the separators and then the trunk zero or country code, so
     * {@code 0901234567}, {@code +84 901 234 567} and {@code 901234} all
     * address the same stored {@code +84901234567}.
     *
     * <p>A prefix is only dropped while something is left to match on, so that
     * searching {@code 0} or {@code 84} narrows by those digits instead of
     * collapsing to the empty term that matches every row.
     */
    private static String phoneSearchTail(String raw) {
        String digits = raw.replaceAll("[\\s\\h.()+-]", "");
        if (digits.length() > 2 && digits.startsWith("84")) {
            return digits.substring(2);
        }
        if (digits.length() > 1 && digits.startsWith("0")) {
            return digits.substring(1);
        }
        return digits;
    }

    private static Specification<User> textSpec(String field, ListParams.Criterion criterion) {
        String value = criterion.value();
        return switch (criterion.operator()) {
            case LIKE -> (root, query, cb) ->
                    cb.like(cb.lower(root.get(field)), ListParams.likePattern(value));
            // email is nullable, and NULL <> 'x' is UNKNOWN rather than true,
            // so a plain notEqual hides every account without an address from
            // a filter they plainly satisfy. Harmless on the NOT NULL columns
            // this also serves: isNull is simply never true for them.
            case NE -> (root, query, cb) -> cb.or(
                    cb.isNull(root.get(field)),
                    cb.notEqual(cb.lower(root.get(field)), value.toLowerCase()));
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
