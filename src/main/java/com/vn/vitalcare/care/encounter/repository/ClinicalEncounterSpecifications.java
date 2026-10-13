package com.vn.vitalcare.care.encounter.repository;

import com.vn.vitalcare.entity.ClinicalEncounter;
import com.vn.vitalcare.entity.EncounterStatus;
import com.vn.vitalcare.share.data.BaseEntitySpecifications;
import com.vn.vitalcare.share.web.ListParams;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/** Translates the parsed query string into a JPA {@link Specification}; deleted rows are excluded. */
public final class ClinicalEncounterSpecifications {

    private ClinicalEncounterSpecifications() {
    }

    public static Specification<ClinicalEncounter> from(ListParams params) {
        List<Specification<ClinicalEncounter>> specs = new ArrayList<>();
        specs.add(BaseEntitySpecifications.notDeleted());

        for (ListParams.Criterion criterion : params.filters()) {
            if ("status".equals(criterion.field())) {
                try {
                    EncounterStatus status = EncounterStatus.from(criterion.value());
                    if (status != null) {
                        specs.add((root, query, cb) -> cb.equal(root.get("status"), status));
                    }
                } catch (IllegalArgumentException e) {
                    specs.add((root, query, cb) -> cb.disjunction());
                }
            }
            // The encounter of one registered visit: "related encounter" on an appointment's page.
            if ("appointment.id".equals(criterion.field()) || "appointmentId".equals(criterion.field())) {
                try {
                    Long appointmentId = Long.valueOf(criterion.value().trim());
                    specs.add((root, query, cb) -> cb.equal(root.get("appointment").get("id"), appointmentId));
                } catch (NumberFormatException e) {
                    specs.add((root, query, cb) -> cb.disjunction());
                }
            }
            // One patient's encounters: the history panel on a patient's page.
            if ("customer.id".equals(criterion.field()) || "customerId".equals(criterion.field())) {
                try {
                    Long customerId = Long.valueOf(criterion.value().trim());
                    specs.add((root, query, cb) -> cb.equal(root.get("customer").get("id"), customerId));
                } catch (NumberFormatException e) {
                    specs.add((root, query, cb) -> cb.disjunction());
                }
            }
        }

        params.search().ifPresent(term -> specs.add((root, query, cb) -> {
            Join<Object, Object> customer = root.join("customer", JoinType.LEFT);
            return cb.or(
                    cb.like(cb.lower(customer.get("user").get("fullName")), ListParams.likePattern(term)),
                    cb.like(cb.lower(customer.get("customerCode")), ListParams.likePattern(term)));
        }));

        List<Long> ids = params.ids();
        if (!ids.isEmpty()) {
            specs.add((root, query, cb) -> root.get("id").in(ids));
        }
        return Specification.allOf(specs);
    }
}
