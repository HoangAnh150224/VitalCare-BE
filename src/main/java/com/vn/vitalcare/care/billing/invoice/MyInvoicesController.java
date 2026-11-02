package com.vn.vitalcare.care.billing.invoice;

import com.vn.vitalcare.share.security.Permissions;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The customer's payment history: {@code /api/my_invoices}, every bill of
 * their account with how each was paid. Pinned to the caller's own profiles.
 */
@RestController
@RequestMapping("/api/my_invoices")
public class MyInvoicesController {

    private final Invoices service;

    public MyInvoicesController(Invoices service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.MY_APPOINTMENTS_READ + "')")
    public List<InvoiceResponse> list() {
        return service.ownAll();
    }

    /** Starts paying one of the caller's own bills through VNPay — a service's as well as a visit's. */
    @org.springframework.web.bind.annotation.PostMapping("/{invoiceId}/vnpay")
    @PreAuthorize("hasAuthority('" + Permissions.MY_APPOINTMENTS_WRITE + "')")
    public java.util.Map<String, String> payOnline(@org.springframework.web.bind.annotation.PathVariable Long invoiceId,
                                                   jakarta.servlet.http.HttpServletRequest request) {
        return java.util.Map.of("url", service.startVnpayForInvoice(invoiceId, MyInvoiceController.clientIp(request)));
    }
}
