package com.vn.vitalcare.entity;

import com.vn.vitalcare.share.data.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * One attempt to pay a bill. A counter payment is paid on the spot; an
 * online one starts {@link PaymentStatus#PENDING} and is settled by the
 * gateway's answer, matched on {@link #transactionCode} (VNPay's
 * {@code vnp_TxnRef}).
 */
@Entity
@Table(name = "payment")
public class Payment extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_id")
    private Invoice invoice;

    @Column(name = "amount", precision = 19, scale = 4)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", length = 64)
    private PaymentMethod method;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32)
    private PaymentStatus status = PaymentStatus.PENDING;

    @Column(name = "transaction_code", length = 128, unique = true)
    private String transactionCode;

    /** The gateway's answer code (VNPay {@code vnp_ResponseCode}); null at the counter. */
    @Column(name = "response_code", length = 8)
    private String responseCode;

    /** The gateway's own reference (VNPay {@code vnp_TransactionNo}). */
    @Column(name = "provider_ref", length = 64)
    private String providerRef;

    @Column(name = "paid_at")
    private OffsetDateTime paidAt;

    protected Payment() {
        // for JPA
    }

    public Payment(Invoice invoice, BigDecimal amount, PaymentMethod method, String transactionCode) {
        this.invoice = invoice;
        this.amount = amount;
        this.method = method;
        this.transactionCode = transactionCode;
    }

    public void succeed(OffsetDateTime when, String responseCode, String providerRef) {
        this.status = PaymentStatus.PAID;
        this.paidAt = when;
        this.responseCode = responseCode;
        this.providerRef = providerRef;
    }

    public void fail(String responseCode, String providerRef) {
        this.status = PaymentStatus.FAILED;
        this.responseCode = responseCode;
        this.providerRef = providerRef;
    }

    public Long getId() {
        return id;
    }

    public Invoice getInvoice() {
        return invoice;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public PaymentMethod getMethod() {
        return method;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public String getTransactionCode() {
        return transactionCode;
    }

    public String getResponseCode() {
        return responseCode;
    }

    public String getProviderRef() {
        return providerRef;
    }

    public OffsetDateTime getPaidAt() {
        return paidAt;
    }
}
