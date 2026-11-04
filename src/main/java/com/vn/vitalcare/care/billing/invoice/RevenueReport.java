package com.vn.vitalcare.care.billing.invoice;

import com.vn.vitalcare.care.billing.Vnd;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A clinic's revenue over a period (Report 1 FE-23): what was paid, split
 * into examination fees and services, and what is still owed, per day bills
 * were issued and in total.
 */
public record RevenueReport(UUID clinicId, LocalDate from, LocalDate to, Day total, List<Day> days) {

    /**
     * @param date            the day; null on the total
     * @param invoices        bills issued
     * @param paidInvoices    of those, settled
     * @param consultationRevenue examination fees on settled bills
     * @param serviceRevenue  service fees on settled bills
     * @param totalRevenue    everything settled
     * @param unpaidInvoices  bills still open
     * @param unpaidAmount    what they come to
     */
    public record Day(LocalDate date, int invoices, int paidInvoices, BigDecimal consultationRevenue,
                      BigDecimal serviceRevenue, BigDecimal totalRevenue, int unpaidInvoices,
                      BigDecimal unpaidAmount) {

        static Day empty(LocalDate date) {
            return new Day(date, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, BigDecimal.ZERO);
        }

        static Day of(LocalDate date, boolean paid, BigDecimal consultation, BigDecimal services, BigDecimal total) {
            return paid
                    ? new Day(date, 1, 1, Vnd.of(consultation), Vnd.of(services), Vnd.of(total), 0, BigDecimal.ZERO)
                    : new Day(date, 1, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 1, Vnd.of(total));
        }

        Day plus(Day other) {
            return new Day(date, invoices + other.invoices, paidInvoices + other.paidInvoices,
                    consultationRevenue.add(other.consultationRevenue), serviceRevenue.add(other.serviceRevenue),
                    totalRevenue.add(other.totalRevenue), unpaidInvoices + other.unpaidInvoices,
                    unpaidAmount.add(other.unpaidAmount));
        }

        Day withDate(LocalDate newDate) {
            return new Day(newDate, invoices, paidInvoices, consultationRevenue, serviceRevenue, totalRevenue,
                    unpaidInvoices, unpaidAmount);
        }
    }
}
