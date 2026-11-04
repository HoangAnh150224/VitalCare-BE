package com.vn.vitalcare.care.examroom.repository;

import com.vn.vitalcare.entity.ExamRoom;
import com.vn.vitalcare.entity.ExamRoomStatus;
import com.vn.vitalcare.share.data.BaseEntitySpecifications;
import com.vn.vitalcare.share.web.ListParams;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/** Translates the parsed query string into a JPA {@link Specification}; deleted rows are excluded. */
public final class ExamRoomSpecifications {

    private ExamRoomSpecifications() {
    }

    public static Specification<ExamRoom> from(ListParams params) {
        List<Specification<ExamRoom>> specs = new ArrayList<>();
        specs.add(BaseEntitySpecifications.notDeleted());

        for (ListParams.Criterion criterion : params.filters()) {
            try {
                switch (criterion.field()) {
                    case "clinicId" -> specs.add(ofClinic(UUID.fromString(criterion.value().trim())));
                    case "status" -> {
                        ExamRoomStatus status = ExamRoomStatus.from(criterion.value());
                        if (status != null) {
                            specs.add((root, query, cb) -> cb.equal(root.get("status"), status));
                        }
                    }
                    default -> {
                    }
                }
            } catch (IllegalArgumentException e) {
                // A value that cannot be a clinic or a status matches nothing.
                specs.add((root, query, cb) -> cb.disjunction());
            }
        }

        params.search().ifPresent(term -> specs.add((root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("code")), ListParams.likePattern(term)),
                cb.like(cb.lower(root.get("name")), ListParams.likePattern(term)))));

        List<Long> ids = params.ids();
        if (!ids.isEmpty()) {
            specs.add((root, query, cb) -> root.get("id").in(ids));
        }
        return Specification.allOf(specs);
    }

    public static Specification<ExamRoom> ofClinic(UUID clinicId) {
        return (root, query, cb) -> cb.equal(root.get("clinicId"), clinicId);
    }
}
