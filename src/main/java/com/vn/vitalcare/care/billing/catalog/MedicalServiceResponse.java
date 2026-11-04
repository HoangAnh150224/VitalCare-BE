package com.vn.vitalcare.care.billing.catalog;

import com.vn.vitalcare.care.billing.Vnd;
import com.vn.vitalcare.entity.MedicalService;
import com.vn.vitalcare.entity.ServiceCategory;
import com.vn.vitalcare.entity.ServiceStatus;
import java.math.BigDecimal;

/** A catalogue entry as the API returns it. */
public record MedicalServiceResponse(
        Long id,
        String code,
        String name,
        String description,
        BigDecimal price,
        ServiceCategory category,
        int turnaroundMinutes,
        ServiceStatus status) {

    public static MedicalServiceResponse from(MedicalService service) {
        return new MedicalServiceResponse(
                service.getId(),
                service.getCode(),
                service.getName(),
                service.getDescription(),
                Vnd.of(service.getPrice()),
                service.getCategory(),
                service.getTurnaroundMinutes(),
                service.getStatus());
    }
}
