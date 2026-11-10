package com.vn.vitalcare.care.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import com.vn.vitalcare.care.appointment.repository.AppointmentRepository;
import com.vn.vitalcare.care.billing.invoice.InvoiceRepository;
import com.vn.vitalcare.entity.Appointment;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** UC-01 step 4: what the patient who pays at the desk is texted. */
class DeskPaymentSmsTest {

    private DeskPaymentSms sms(String template) {
        return new DeskPaymentSms(mock(AppointmentRepository.class), mock(InvoiceRepository.class),
                mock(BookingConfirmationSms.class), template);
    }

    @Test
    @DisplayName("the text gives the day, the number, the code and the amount to bring")
    void text() {
        Appointment visit = new Appointment();
        visit.setAppointmentDate(LocalDate.of(2026, 11, 11));
        visit.setQueueNumber(5);
        visit.setBookingCode("AB12CD34");

        assertEquals("VitalCare: da xac nhan lich kham ngay 11/11/2026, so thu tu 5, ma AB12CD34. "
                + "Vui long tra phi kham 150.000d tai quay le tan khi toi kham.",
                sms("").text(visit, new BigDecimal("150000.0000")));
        assertEquals("Lich 11/11/2026 so 5 tien theo bang gia", sms("Lich {date} so {number} tien {amount}").text(visit, null));
    }
}
