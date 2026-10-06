package com.vn.vitalcare.care.device.repository;

import com.vn.vitalcare.entity.DeviceStatus;
import com.vn.vitalcare.entity.MedicalDevice;
import com.vn.vitalcare.share.data.BaseEntitySpecifications;
import com.vn.vitalcare.share.web.ListParams;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/** Translates the parsed query string into a JPA {@link Specification}; deleted rows are excluded. */
public final class MedicalDeviceSpecifications {

    private MedicalDeviceSpecifications() {
    }

    public static Specification<MedicalDevice> from(ListParams params) {
        List<Specification<MedicalDevice>> specs = new ArrayList<>();
        specs.add(BaseEntitySpecifications.notDeleted());

        for (ListParams.Criterion criterion : params.filters()) {
            if ("status".equals(criterion.field())) {
                try {
                    DeviceStatus status = DeviceStatus.from(criterion.value());
                    if (status != null) {
                        specs.add((root, query, cb) -> cb.equal(root.get("status"), status));
                    }
                } catch (IllegalArgumentException e) {
                    specs.add((root, query, cb) -> cb.disjunction());
                }
            }
        }

        params.search().ifPresent(term -> specs.add((root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("deviceCode")), ListParams.likePattern(term)),
                cb.like(cb.lower(root.get("serialNumber")), ListParams.likePattern(term)),
                cb.like(cb.lower(root.get("model")), ListParams.likePattern(term)))));

        List<Long> ids = params.ids();
        if (!ids.isEmpty()) {
            specs.add((root, query, cb) -> root.get("id").in(ids));
        }
        return Specification.allOf(specs);
    }
}
