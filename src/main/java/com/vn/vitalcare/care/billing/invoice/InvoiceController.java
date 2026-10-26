package com.vn.vitalcare.care.billing.invoice;

import com.vn.vitalcare.entity.PaymentMethod;
import com.vn.vitalcare.share.security.Permissions;
import com.vn.vitalcare.share.web.ListParams;
import com.vn.vitalcare.share.web.ListResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/invoices} — the clinic's bills, payments taken at the counter, and the revenue report. */
@RestController
@RequestMapping("/api/invoices")
public class InvoiceController {

    private final Invoices service;

    public InvoiceController(Invoices service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.INVOICES_READ + "')")
    public ResponseEntity<List<InvoiceResponse>> list(@RequestParam MultiValueMap<String, String> query) {
        Page<InvoiceResponse> page = service.list(new ListParams(query));
        return ListResponse.of(page.getContent(), page.getTotalElements());
    }

    /** Before {@code /{id}}, so "report" is not read as an id. */
    @GetMapping("/report")
    @PreAuthorize("hasAuthority('" + Permissions.INVOICES_REPORT + "')")
    public RevenueReport report(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.report(from, to);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.INVOICES_READ + "')")
    public InvoiceResponse get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping("/{id}/payments")
    @PreAuthorize("hasAuthority('" + Permissions.INVOICES_WRITE + "')")
    public InvoiceResponse pay(@PathVariable Long id, @Valid @RequestBody CounterPayment request) {
        return service.payAtCounter(id, request.method());
    }

    /** {@code POST /invoices/{id}/payments}: settled at the counter, in cash or by transfer. */
    public record CounterPayment(@NotNull(message = "Pick how it was paid") PaymentMethod method) {
    }
}
