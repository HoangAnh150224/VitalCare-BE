package com.vn.vitalcare.care.serviceorder;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.vn.vitalcare.care.billing.Vnd;
import com.vn.vitalcare.entity.ServiceCategory;
import com.vn.vitalcare.entity.ServiceOrder;
import com.vn.vitalcare.entity.ServiceOrderStatus;
import java.math.BigDecimal;
import org.hibernate.Hibernate;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** A service order and its result, with the names of who ordered, performed and reviewed it. */
public record ServiceOrderResponse(
        Long id,
        Long encounterId,
        Patient patient,
        Service service,
        BigDecimal unitPrice,
        String instruction,
        ServiceOrderStatus status,
        Instant orderedAt,
        String orderedBy,
        OffsetDateTime expectedResultAt,
        String performedBy,
        OffsetDateTime startedAt,
        String resultText,
        OffsetDateTime resultAt,
        String reviewedBy,
        OffsetDateTime reviewedAt,
        OffsetDateTime cancelledAt,
        String cancelReason,
        List<File> files,
        @JsonProperty("_can")
        Map<String, Boolean> can) {

    public record Patient(Long id, String customerCode, String fullName) {
    }

    public record Service(Long id, String code, String name, ServiceCategory category) {
    }

    public record File(Long id, String name, String contentType, long sizeBytes, Instant uploadedAt) {
    }

    /**
     * @param names users' full names by id, for the audit columns; a name
     *              missing from it shows as null
     */
    public static ServiceOrderResponse from(ServiceOrder order, Map<Long, String> names, Map<String, Boolean> can) {
        var customer = order.getCustomer();
        var service = order.getService();
        return new ServiceOrderResponse(
                order.getId(),
                order.getEncounter().getId(),
                new Patient(customer.getId(), customer.getCustomerCode(), customer.getFullName()),
                new Service(service.getId(), service.getCode(), service.getName(), service.getCategory()),
                Vnd.of(order.getUnitPrice()),
                order.getInstruction(),
                order.getStatus(),
                order.getCreatedAt(),
                names.get(order.getCreatedById()),
                order.getExpectedResultAt(),
                names.get(order.getPerformedById()),
                order.getStartedAt(),
                order.getResultText(),
                order.getResultAt(),
                names.get(order.getReviewedById()),
                order.getReviewedAt(),
                order.getCancelledAt(),
                order.getCancelReason(),
                // A page of rows comes without its files; only a single order or an encounter's carry them.
                !Hibernate.isInitialized(order.getFiles()) ? List.of() : order.getFiles().stream()
                        .filter(file -> file.getDeletedAt() == null)
                        .map(file -> new File(file.getId(), file.getOriginalName(), file.getContentType(),
                                file.getSizeBytes(), file.getCreatedAt()))
                        .toList(),
                can);
    }
}
