package com.vn.vitalcare.care.firstaid;

import com.vn.vitalcare.entity.FirstAidArticle;
import com.vn.vitalcare.entity.FirstAidCategory;
import com.vn.vitalcare.entity.FirstAidStatus;
import com.vn.vitalcare.share.data.BaseEntitySpecifications;
import com.vn.vitalcare.share.web.ListParams;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/** Translates the parsed query string into a JPA {@link Specification}; deleted rows are excluded. */
final class FirstAidSpecifications {

    private FirstAidSpecifications() {
    }

    static Specification<FirstAidArticle> from(ListParams params) {
        List<Specification<FirstAidArticle>> specs = new ArrayList<>();
        specs.add(BaseEntitySpecifications.notDeleted());

        for (ListParams.Criterion criterion : params.filters()) {
            try {
                switch (criterion.field()) {
                    case "status" -> {
                        FirstAidStatus status = FirstAidStatus.from(criterion.value());
                        if (status != null) {
                            specs.add((root, query, cb) -> criterion.operator() == ListParams.Operator.NE
                                    ? cb.notEqual(root.get("status"), status)
                                    : cb.equal(root.get("status"), status));
                        }
                    }
                    case "category" -> {
                        FirstAidCategory category = FirstAidCategory.from(criterion.value());
                        if (category != null) {
                            specs.add((root, query, cb) -> cb.equal(root.get("category"), category));
                        }
                    }
                    default -> {
                    }
                }
            } catch (IllegalArgumentException e) {
                specs.add((root, query, cb) -> cb.disjunction());
            }
        }

        params.search().ifPresent(term -> specs.add((root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("title")), ListParams.likePattern(term)),
                cb.like(cb.lower(root.get("summary")), ListParams.likePattern(term)))));

        List<Long> ids = params.ids();
        if (!ids.isEmpty()) {
            specs.add((root, query, cb) -> root.get("id").in(ids));
        }
        return Specification.allOf(specs);
    }
}
