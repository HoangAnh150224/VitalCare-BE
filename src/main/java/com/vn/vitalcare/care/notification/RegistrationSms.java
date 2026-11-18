package com.vn.vitalcare.care.notification;

import com.vn.vitalcare.care.registration.service.AccountRegistered;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * A person who has just registered is told so by SMS, on the number they
 * verified. Sent like the booking confirmation ({@link BookingConfirmationSms}:
 * off the request thread, retried, then logged).
 */
@Component
public class RegistrationSms {

    static final String TEXT = "VitalCare: Dang ky tai khoan thanh cong. Ban co the dang nhap de dat lich kham.";

    private final BookingConfirmationSms sender;

    public RegistrationSms(BookingConfirmationSms sender) {
        this.sender = sender;
    }

    /** After the registration commits: an account that was rolled back is never announced. */
    @TransactionalEventListener
    public void onRegistered(AccountRegistered event) {
        Thread.ofVirtual().name("registration-sms").start(() -> sender.send(event.phone(), TEXT));
    }
}
