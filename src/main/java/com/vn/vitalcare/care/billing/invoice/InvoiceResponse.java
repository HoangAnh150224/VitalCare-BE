package com.vn.vitalcare.care.billing.invoice;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.vn.vitalcare.care.billing.Vnd;
import com.vn.vitalcare.entity.Invoice;
import com.vn.vitalcare.entity.InvoiceStatus;
import com.vn.vitalcare.entity.PaymentMethod;
import com.vn.vitalcare.entity.PaymentStatus;
import com.vn.vitalcare.entity.ServiceCategory;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A bill with its lines and payment attempts. Built inside the transaction that read it. */
public record InvoiceResponse(
        Long id,
        String invoiceNumber,
        InvoiceStatus status,
        Patient patient,
        Clinic clinic,
        Long appointmentId,
        Long encounterId,
        OffsetDateTime issuedAt,
        OffsetDateTime paidAt,
        BigDecimal subtotal,
        BigDecimal totalAmount,
        List<Line> items,
        List<PaymentRow> payments,
        @JsonProperty("_can")
        Map<String, Boolean> can) {

    public record Patient(Long id, String customerCode, String fullName, String phone) {
    }

    public record Clinic(UUID id, String name) {
    }

    public record Line(String description, ServiceCategory category, int quantity, BigDecimal unitPrice,
                       BigDecimal amount) {
    }

    public record PaymentRow(Long id, PaymentMethod method, PaymentStatus status, BigDecimal amount,
                             String transactionCode, OffsetDateTime paidAt, Instant createdAt) {
    }

    /** @param can row flags for staff; empty for the customer's own view */
    static InvoiceResponse from(Invoice invoice, Map<String, Boolean> can) {
        var customer = invoice.getCustomer();
        var clinic = invoice.getClinic();
        return new InvoiceResponse(
                invoice.getId(),
                invoice.getInvoiceNumber(),
                invoice.getStatus(),
                new Patient(customer.getId(), customer.getCustomerCode(), customer.getFullName(), customer.getPhone()),
                clinic == null ? null : new Clinic(clinic.getClinicId(), clinic.getClinicName()),
                invoice.getAppointment() == null ? null : invoice.getAppointment().getId(),
                invoice.getEncounter() == null ? null : invoice.getEncounter().getId(),
                invoice.getIssuedAt(),
                invoice.getPaidAt(),
                Vnd.of(invoice.getSubtotal()),
                Vnd.of(invoice.getTotalAmount()),
                invoice.getItems().stream()
                        .map(item -> new Line(item.getDescription(),
                                item.getService() == null ? null : item.getService().getCategory(),
                                item.getQuantity() == null ? 1 : item.getQuantity(),
                                Vnd.of(item.getUnitPrice()), Vnd.of(item.getAmount())))
                        .toList(),
                invoice.getPayments().stream()
                        .filter(payment -> payment.getDeletedAt() == null)
                        .map(payment -> new PaymentRow(payment.getId(), payment.getMethod(), payment.getStatus(),
                                Vnd.of(payment.getAmount()), payment.getTransactionCode(), payment.getPaidAt(),
                                payment.getCreatedAt()))
                        .toList(),
                can);
    }
}
