package com.vn.vitalcare.care.billing.sepay;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

/**
 * One transaction SePay saw on the clinic's account, as its webhook posts it.
 * {@code code} is the payment code SePay recognised in the content, when it did;
 * {@code transferType} is {@code in} for money received.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SepayTransfer(
        Long id,
        String gateway,
        String transactionDate,
        String accountNumber,
        String code,
        String content,
        String transferType,
        BigDecimal transferAmount,
        String referenceCode) {
}
