package com.vn.vitalcare.care.notification;

import com.vn.vitalcare.care.appointment.repository.AppointmentRepository;
import com.vn.vitalcare.care.appointment.service.VisitEvents;
import com.vn.vitalcare.care.registration.service.EsmsOtpSender;
import com.vn.vitalcare.entity.Appointment;
import com.vn.vitalcare.entity.AppointmentStatus;
import com.vn.vitalcare.share.phone.PhoneNumbers;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * BR-12: a visit booked online is confirmed by SMS — its day, number, code
 * and whether the fee is paid — tried up to three more times when the
 * provider fails, then logged; the confirmation in the app
 * ({@link VisitNotifications#onRegistered}) is there either way.
 *
 * <p>Sent through eSMS only with a registered template
 * ({@code app.sms.esms.booking-template}, placeholders {@code {date}},
 * {@code {number}}, {@code {code}}, {@code {payment}}): a brandname carries only
 * the texts it was registered with. Without one the text goes to the log.
 * Walk-ins are not texted: they are at the desk, checked in, when it commits.
 */
@Component
public class BookingConfirmationSms {

    private static final Logger log = LoggerFactory.getLogger(BookingConfirmationSms.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    /** The first send and BR-12's three more. */
    static final int ATTEMPTS = 4;

    private final AppointmentRepository appointments;
    private final ObjectProvider<EsmsOtpSender> esms;
    private final String template;
    private final Duration retryAfter;

    public BookingConfirmationSms(AppointmentRepository appointments, ObjectProvider<EsmsOtpSender> esms,
                                  @Value("${app.sms.esms.booking-template:}") String template,
                                  @Value("${app.sms.booking-retry-after:5s}") Duration retryAfter) {
        this.appointments = appointments;
        this.esms = esms;
        this.template = template.trim();
        this.retryAfter = retryAfter;
    }

    /** After the booking commits: a rolled-back one is never confirmed. */
    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onRegistered(VisitEvents.Registered event) {
        Appointment visit = appointments.findForNotice(event.appointmentId()).orElse(null);
        if (visit == null || visit.getStatus() != AppointmentStatus.BOOKED || visit.getCustomer().getPhone() == null) {
            return;
        }
        String phone = visit.getCustomer().getPhone();
        String text = text(visit);
        Thread.ofVirtual().name("booking-sms").start(() -> send(phone, text));
    }

    String text(Appointment visit) {
        String date = DAY.format(visit.getAppointmentDate());
        String number = visit.getQueueNumber() == null ? "-" : String.valueOf(visit.getQueueNumber());
        String payment = visit.getPaidAt() != null ? "da thanh toan" : "chua thanh toan";
        String body = template.isEmpty()
                ? "VitalCare: da dat lich kham ngay {date}, so thu tu {number}, ma {code}. Phi kham: {payment}."
                : template;
        return body.replace("{date}", date).replace("{number}", number)
                .replace("{code}", visit.getBookingCode()).replace("{payment}", payment);
    }

    /** On this thread, with BR-12's retries; the outcome is only ever logged. */
    void send(String phone, String text) {
        send(phone, text, !template.isEmpty());
    }

    /** The same for any text: sent only when it follows a template registered with the brandname, else logged. */
    void send(String phone, String text, boolean registered) {
        EsmsOtpSender sender = registered ? esms.getIfAvailable() : null;
        if (sender == null) {
            log.info("Booking confirmation for {} (no SMS template configured, not sent): {}",
                    PhoneNumbers.mask(phone), text);
            return;
        }
        String error = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                error = sender.deliver(phone, text);
            } catch (RuntimeException e) {
                error = e.getMessage();
            }
            if (error == null) {
                log.info("Booking confirmation sent by SMS to {}", PhoneNumbers.mask(phone));
                return;
            }
            if (attempt < ATTEMPTS) {
                try {
                    Thread.sleep(retryAfter.multipliedBy(attempt));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        log.error("Booking confirmation SMS to {} failed after {} attempts: {}; the app shows it",
                PhoneNumbers.mask(phone), ATTEMPTS, error);
    }
}
