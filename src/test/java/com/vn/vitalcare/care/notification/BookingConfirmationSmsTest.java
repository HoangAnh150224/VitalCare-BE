package com.vn.vitalcare.care.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.care.appointment.repository.AppointmentRepository;
import com.vn.vitalcare.care.registration.service.EsmsOtpSender;
import com.vn.vitalcare.entity.Appointment;
import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** BR-12: what the booking confirmation says, and the retries before giving up. */
class BookingConfirmationSmsTest {

    private final EsmsOtpSender esms = mock(EsmsOtpSender.class);

    @SuppressWarnings("unchecked")
    private BookingConfirmationSms sms(String template) {
        ObjectProvider<EsmsOtpSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(esms);
        return new BookingConfirmationSms(mock(AppointmentRepository.class), provider, template, Duration.ZERO);
    }

    @Test
    @DisplayName("the text gives the day, the number, the code and whether the fee is paid")
    void text() {
        Appointment visit = new Appointment();
        visit.setAppointmentDate(LocalDate.of(2026, 11, 9));
        visit.setQueueNumber(7);
        visit.setBookingCode("K7M29QXA");

        assertEquals("VitalCare: da dat lich kham ngay 09/11/2026, so thu tu 7, ma K7M29QXA. Phi kham: chua thanh toan.",
                sms("").text(visit));
        assertEquals("Lich 09/11/2026 so 7", sms("Lich {date} so {number}").text(visit));
    }

    @Test
    @DisplayName("a refused send is tried three more times, then given up; one that goes through stops there")
    void retries() {
        when(esms.deliver(any(), any())).thenReturn("CodeResult=99");
        sms("Lich {date}").send("+84912345678", "x");
        verify(esms, times(BookingConfirmationSms.ATTEMPTS)).deliver(eq("+84912345678"), eq("x"));

        EsmsOtpSender second = esms;
        when(second.deliver(any(), any())).thenReturn("CodeResult=99", (String) null);
        sms("Lich {date}").send("+84912345679", "y");
        verify(esms, times(2)).deliver(eq("+84912345679"), eq("y"));
    }

    @Test
    @DisplayName("without a registered template nothing is sent: the text is logged")
    void noTemplateNoSend() {
        sms("").send("+84912345678", "x");
        verify(esms, never()).deliver(any(), any());
    }
}
