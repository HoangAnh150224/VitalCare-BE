package com.vn.vitalcare.care.billing.invoice;

import com.vn.vitalcare.share.security.Permissions;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The customer's own bill, on their appointment: {@code /api/my_appointments/{id}/invoice}.
 * Pinned to the caller's own appointments, whatever the id.
 */
@RestController
@RequestMapping("/api/my_appointments/{appointmentId}/invoice")
public class MyInvoiceController {

    private final Invoices service;

    public MyInvoiceController(Invoices service) {
        this.service = service;
    }

    /** The bill, and whether it can be paid online here. 404 until the visit is concluded. */
    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.MY_APPOINTMENTS_READ + "')")
    public OwnInvoice get(@PathVariable Long appointmentId) {
        return new OwnInvoice(service.ownForAppointment(appointmentId), service.onlinePaymentEnabled());
    }

    /** Starts a VNPay payment: the URL to send the customer to. */
    @PostMapping("/vnpay")
    @PreAuthorize("hasAuthority('" + Permissions.MY_APPOINTMENTS_WRITE + "')")
    public Map<String, String> payOnline(@PathVariable Long appointmentId, HttpServletRequest request) {
        return Map.of("url", service.startVnpay(appointmentId, clientIp(request)));
    }

    public record OwnInvoice(InvoiceResponse invoice, boolean onlinePayment) {
    }

    /** The first hop of X-Forwarded-For behind a proxy (Railway), else the connection's own address. */
    static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
