package com.vn.vitalcare.care.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import com.vn.vitalcare.care.appointment.repository.AppointmentRepository;
import com.vn.vitalcare.care.billing.invoice.InvoiceRepository;
import com.vn.vitalcare.entity.Appointment;
import com.vn.vitalcare.entity.Customer;
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
        Customer patient = new Customer();
        patient.setFullName("Trần Thu Trang");
        Appointment visit = new Appointment();
        visit.setCustomer(patient);
        visit.setAppointmentDate(LocalDate.of(2026, 11, 11));
        visit.setQueueNumber(5);
        visit.setBookingCode("AB12CD34");

        assertEquals("VitalCare: Tran Thu Trang dang ky kham thanh cong cho Tran Thu Trang ngay 11/11/2026, "
                + "STT 5, ma AB12CD34. Vui long tra 150.000d tai quay le tan.",
                sms("").text(visit, new BigDecimal("150000.0000")));
        assertEquals("Lich 11/11/2026 so 5 tien theo bang gia", sms("Lich {date} so {number} tien {amount}").text(visit, null));
    }
}
