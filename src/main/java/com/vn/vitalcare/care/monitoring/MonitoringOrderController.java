package com.vn.vitalcare.care.monitoring;

import com.vn.vitalcare.entity.MonitoringOrder;
import com.vn.vitalcare.share.security.Permissions;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/monitoring_orders} — BF-04 monitoring orders and the patient's consent. */
@RestController
@RequestMapping("/api/monitoring_orders")
public class MonitoringOrderController {

    private final MonitoringOrders orders;

    public MonitoringOrderController(MonitoringOrders orders) {
        this.orders = orders;
    }

    public record OrderResponse(Long id, Long customerId, String customerName, Long encounterId, String reason,
                                String decision, String consentNote, String status, OffsetDateTime decidedAt,
                                String orderedByName, OffsetDateTime endedAt, String endNote) {
        static OrderResponse of(MonitoringOrder o) {
            return new OrderResponse(o.getId(), o.getCustomer().getId(), o.getCustomer().getFullName(),
                    o.getEncounter() == null ? null : o.getEncounter().getId(), o.getReason(), o.getDecision(),
                    o.getConsentNote(), o.getStatus(), o.getDecidedAt(), o.getOrderedByName(), o.getEndedAt(),
                    o.getEndNote());
        }
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.MONITORING_ORDERS_READ + "')")
    public List<OrderResponse> forPatient(@RequestParam Long customerId) {
        return orders.forPatient(customerId).stream().map(OrderResponse::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('" + Permissions.MONITORING_ORDERS_WRITE + "')")
    public OrderResponse create(@Valid @RequestBody MonitoringOrders.OrderRequest request) {
        return OrderResponse.of(orders.create(request));
    }

    @PostMapping("/{id}/end")
    @PreAuthorize("hasAuthority('" + Permissions.MONITORING_ORDERS_WRITE + "')")
    public OrderResponse end(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body) {
        return OrderResponse.of(orders.end(id, body == null ? null : body.get("note")));
    }
}
