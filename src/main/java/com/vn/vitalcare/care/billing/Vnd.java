package com.vn.vitalcare.care.billing;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Amounts in đồng: whole numbers, stored with four decimals by the 008 schema, shown and sent without them. */
public final class Vnd {

    private Vnd() {
    }

    public static BigDecimal of(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO : amount.setScale(0, RoundingMode.HALF_UP);
    }
}
