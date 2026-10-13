package com.vn.vitalcare.care.schedule.repository;

import com.vn.vitalcare.entity.DoctorSchedule;
import com.vn.vitalcare.entity.ScheduleStatus;
import com.vn.vitalcare.share.data.BaseEntitySpecifications;
import com.vn.vitalcare.share.web.ListParams;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/** Translates the parsed query string into a JPA {@link Specification}; deleted rows are excluded. */
public final class DoctorScheduleSpecifications {

    private DoctorScheduleSpecifications() {
    }

    public static Specification<DoctorSchedule> from(ListParams params) {
        List<Specification<DoctorSchedule>> specs = new ArrayList<>();
        specs.add(BaseEntitySpecifications.notDeleted());

        for (ListParams.Criterion criterion : params.filters()) {
            try {
                switch (criterion.field()) {
                    case "status" -> {
                        ScheduleStatus status = ScheduleStatus.from(criterion.value());
                        if (status != null) {
                            specs.add((root, query, cb) -> cb.equal(root.get("status"), status));
                        }
                    }
                    case "employee.id", "employeeId" -> {
                        Long employeeId = Long.valueOf(criterion.value().trim());
                        specs.add((root, query, cb) -> cb.equal(root.get("employee").get("id"), employeeId));
                    }
                    // workDate, workDate_gte, workDate_lte: one day or a range of them.
                    case "workDate" -> {
                        LocalDate date = LocalDate.parse(criterion.value().trim());
                        specs.add((root, query, cb) -> switch (criterion.operator()) {
                            case GTE -> cb.greaterThanOrEqualTo(root.get("workDate"), date);
                            case LTE -> cb.lessThanOrEqualTo(root.get("workDate"), date);
                            default -> cb.equal(root.get("workDate"), date);
                        });
                    }
                    default -> {
                    }
                }
            } catch (IllegalArgumentException | DateTimeParseException e) {
                // A value that cannot be a status, an id or a date matches nothing.
                specs.add((root, query, cb) -> cb.disjunction());
            }
        }

        params.search().ifPresent(term -> specs.add((root, query, cb) ->
                cb.like(cb.lower(root.get("employee").get("user").get("fullName")), ListParams.likePattern(term))));

        List<Long> ids = params.ids();
        if (!ids.isEmpty()) {
            specs.add((root, query, cb) -> root.get("id").in(ids));
        }
        return Specification.allOf(specs);
    }

    /**
     * Another period of the same doctor's day that shares time with
     * {@code start}–{@code end}. Touching periods (one ends 11:30, the next
     * starts 11:30) do not overlap.
     *
     * <p>Not scoped: it asks about one doctor's own rows, to refuse a clash,
     * and returns nothing to the caller.
     *
     * @param exceptId the row being edited, which does not clash with itself; null on create
     */
    public static Specification<DoctorSchedule> overlapping(Long employeeId, LocalDate date, LocalTime start,
                                                            LocalTime end, Long exceptId) {
        return (root, query, cb) -> cb.and(
                cb.isNull(root.get("deletedAt")),
                cb.equal(root.get("employee").get("id"), employeeId),
                cb.equal(root.get("workDate"), date),
                cb.lessThan(root.get("startTime"), end),
                cb.greaterThan(root.get("endTime"), start),
                exceptId == null ? cb.conjunction() : cb.notEqual(root.get("id"), exceptId));
    }
}
