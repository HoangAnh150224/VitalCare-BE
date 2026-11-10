package com.vn.vitalcare.care.notification;

import com.vn.vitalcare.care.appointment.repository.AppointmentRepository;
import com.vn.vitalcare.care.appointment.service.VisitEvents;
import com.vn.vitalcare.care.billing.invoice.InvoiceRepository;
import com.vn.vitalcare.entity.Appointment;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * UC-01 step 4: the patient chose to pay the examination fee at the desk, and
 * is told by SMS that the visit stands and what to bring - its day, number,
 * code and the amount. Sent like the booking confirmation
 * ({@link BookingConfirmationSms}: retried, then logged), and only with a
 * template registered for it ({@code app.sms.esms.desk-template}, placeholders
 * {@code {owner}}, {@code {patient}}, {@code {date}}, {@code {number}}, {@code {code}}, {@code {amount}}); without
 * one the text goes to the log.
 */
@Component
public class DeskPaymentSms {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final AppointmentRepository appointments;
    private final InvoiceRepository invoices;
    private final BookingConfirmationSms sender;
    private final String template;

    public DeskPaymentSms(AppointmentRepository appointments, InvoiceRepository invoices, BookingConfirmationSms sender,
                          @Value("${app.sms.esms.desk-template:}") String template) {
        this.appointments = appointments;
        this.invoices = invoices;
        this.sender = sender;
        this.template = template.trim();
    }

    /** After the choice commits. */
    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onChosen(VisitEvents.DeskPaymentChosen event) {
        Appointment visit = appointments.findForNotice(event.appointmentId()).orElse(null);
        // To whoever booked it, as the booking confirmation.
        String phone = visit == null ? null : BookingConfirmationSms.accountOf(visit).getPhone();
        if (phone == null) {
            return;
        }
        BigDecimal amount = invoices.findExamOf(visit.getId()).map(invoice -> invoice.getTotalAmount()).orElse(null);
        String text = text(visit, amount);
        Thread.ofVirtual().name("desk-payment-sms").start(() -> sender.send(phone, text, !template.isEmpty()));
    }

    String text(Appointment visit, BigDecimal amount) {
        String number = visit.getQueueNumber() == null ? "-" : String.valueOf(visit.getQueueNumber());
        String money = amount == null ? "theo bang gia" : NumberFormat.getIntegerInstance(Locale.forLanguageTag("vi-VN"))
                .format(amount) + "d";
        String body = template.isEmpty()
                ? "VitalCare: {owner} dang ky kham thanh cong cho {patient} ngay {date}, STT {number}, "
                        + "ma {code}. Vui long tra {amount} tai quay le tan."
                : template;
        return BookingConfirmationSms.names(body, visit).replace("{date}", DAY.format(visit.getAppointmentDate())).replace("{number}", number)
                .replace("{code}", visit.getBookingCode()).replace("{amount}", money);
    }
}
