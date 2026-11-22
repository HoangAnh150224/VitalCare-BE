package com.vn.vitalcare.care.billing.invoice;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Report-5.1 BE_Infra: RevenueReport.Day, how one bill counts towards a day and days add up (FE-23 Revenue Report). */
class RevenueReportDayUnitTest {

    private static final LocalDate D = LocalDate.of(2026, 11, 22);

    private static BigDecimal vnd(String v) {
        return new BigDecimal(v);
    }

    @Test
    @DisplayName("A settled bill counts as paid revenue split into examination fee and services; nothing owed")
    void rr01_paidBill() {
        RevenueReport.Day d = RevenueReport.Day.of(D, true, vnd("150000"), vnd("50000"), vnd("200000"));
        assertThat(d.invoices()).isEqualTo(1);
        assertThat(d.paidInvoices()).isEqualTo(1);
        assertThat(d.consultationRevenue()).isEqualByComparingTo("150000");
        assertThat(d.serviceRevenue()).isEqualByComparingTo("50000");
        assertThat(d.totalRevenue()).isEqualByComparingTo("200000");
        assertThat(d.unpaidInvoices()).isZero();
        assertThat(d.unpaidAmount()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("An open bill brings no revenue; its total is what is still owed")
    void rr02_unpaidBill() {
        RevenueReport.Day d = RevenueReport.Day.of(D, false, vnd("150000"), vnd("50000"), vnd("200000"));
        assertThat(d.paidInvoices()).isZero();
        assertThat(d.totalRevenue()).isEqualByComparingTo("0");
        assertThat(d.unpaidInvoices()).isEqualTo(1);
        assertThat(d.unpaidAmount()).isEqualByComparingTo("200000");
    }

    @Test
    @DisplayName("Days add field by field; the date of the left-hand day is kept")
    void rr03_plus() {
        RevenueReport.Day sum = RevenueReport.Day.of(D, true, vnd("150000"), BigDecimal.ZERO, vnd("150000"))
                .plus(RevenueReport.Day.of(D.plusDays(1), false, BigDecimal.ZERO, vnd("80000"), vnd("80000")));
        assertThat(sum.date()).isEqualTo(D);
        assertThat(sum.invoices()).isEqualTo(2);
        assertThat(sum.paidInvoices()).isEqualTo(1);
        assertThat(sum.totalRevenue()).isEqualByComparingTo("150000");
        assertThat(sum.unpaidInvoices()).isEqualTo(1);
        assertThat(sum.unpaidAmount()).isEqualByComparingTo("80000");
    }

    @Test
    @DisplayName("Boundary: an empty day is all zeros and adding it changes no figure; withDate only moves the date")
    void rr04_emptyAndWithDate() {
        RevenueReport.Day paid = RevenueReport.Day.of(D, true, vnd("1"), vnd("2"), vnd("3"));
        RevenueReport.Day same = paid.plus(RevenueReport.Day.empty(null));
        assertThat(same.invoices()).isEqualTo(1);
        assertThat(same.totalRevenue()).isEqualByComparingTo("3");
        assertThat(RevenueReport.Day.empty(D).invoices()).isZero();
        RevenueReport.Day moved = paid.withDate(null);
        assertThat(moved.date()).isNull();
        assertThat(moved.totalRevenue()).isEqualByComparingTo("3");
        assertThat(moved.paidInvoices()).isEqualTo(1);
    }
}
