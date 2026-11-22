package com.vn.vitalcare.it;

import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Appointment flow end to end (FE-10 Create Appointment, Walk-in Registration, Appointment Details,
 * Appointment Check-in, Daily Examination Queue; FE-23 Clinic Payment Confirmation; FE-18 Create Clinical
 * Encounter), following the business flow BF-02: a visit is booked at a clinic, checked in at the desk, the
 * examination fee is paid, and only then is the patient in the doctor's queue and can be examined.
 */
class AppointmentFlowIT extends IntegrationTestBase {

    private static final long DOCTOR = 1L; // BS. Nguyễn Minh Khoa, Tim mạch, VitalCare Clinic
    private final LocalDate today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));
    private String desk;

    @BeforeEach
    void doctorWorksAllDayEveryDay() throws Exception {
        jdbc.update("delete from doctor_working_hours where employee_id = ?", DOCTOR);
        for (int day = 1; day <= 7; day++) {
            jdbc.update("insert into doctor_working_hours (employee_id, day_of_week, start_time, end_time, visit_minutes, "
                    + "created_at, version) values (?, ?, '00:00', '23:55', 5, now(), 0)", DOCTOR, day);
        }
        desk = tokenFor("0900000002", "Manager@123");
    }

    /** A new customer account whose own profile is complete enough to book (birth date, gender, ID number). */
    private String customer(String phone) throws Exception {
        String token = register(phone, "Benh Nhan", "Passw0rd!");
        sync();
        jdbc.update("update customer set date_of_birth = '1990-05-01', gender = 'female', national_id = ?, "
                + "national_id_type = 'cccd' where user_id = (select id from users where phone = ?)", "00" + phone, intl(phone));
        return token;
    }

    private ResultActions bookOnline(String token, LocalDate date) throws Exception {
        return mvc.perform(post("/api/my_appointments").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"clinicId":"%s","appointmentDate":"%s","reason":"Đau đầu, sốt nhẹ","department":"Tim mạch"}"""
                        .formatted(CLINIC_1, date)));
    }

    private Long book(String token) throws Exception {
        return Long.valueOf(json(bookOnline(token, today).andExpect(status().isCreated()), "$.id"));
    }

    private ResultActions checkIn(Long appointmentId) throws Exception {
        return mvc.perform(post("/api/appointments/" + appointmentId + "/check-in").header("Authorization", bearer(desk)));
    }

    /** The desk takes the examination fee in cash (Clinic Payment Confirmation). */
    private ResultActions payAtDesk(Long appointmentId) throws Exception {
        String invoice = json(mvc.perform(get("/api/invoices/exam").param("appointmentId", appointmentId.toString())
                .header("Authorization", bearer(desk))).andExpect(status().isOk()), "$.id");
        return mvc.perform(post("/api/invoices/" + invoice + "/payments").header("Authorization", bearer(desk))
                .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"CASH\"}"));
    }

    private ResultActions queueOf(String token, Long appointmentId) throws Exception {
        return mvc.perform(get("/api/my_appointments/" + appointmentId + "/queue").header("Authorization", bearer(token)));
    }

    // ---------------------------------------------------------------- Create Appointment / Details
    @Test
    void apt01_onlineBookingGetsCodeQueueNumberAndSharedQueue() throws Exception {
        String token = customer(newPhone());
        bookOnline(token, today).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("booked"))
                .andExpect(jsonPath("$.bookingCode").isNotEmpty())
                .andExpect(jsonPath("$.queueNumber").isNumber())
                .andExpect(jsonPath("$.clinic.id").value(CLINIC_1))
                .andExpect(jsonPath("$.department").value("Tim mạch"))
                .andExpect(jsonPath("$.doctor").isEmpty());
    }

    @Test
    void apt02_incompleteProfileCannotBook() throws Exception {
        String token = register(newPhone(), "Chua Du Ho So", "Passw0rd!");
        bookOnline(token, today).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors.patientId").value(startsWith("Fill in this profile's date of birth, gender and ID number")));
    }

    @Test
    void apt03_sameClinicSameDayOnlyOnce_pastDateRefused() throws Exception {
        String phone = newPhone();
        String token = customer(phone);
        book(token);
        bookOnline(token, today).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("There is already a visit registered at that clinic on that day"));
        bookOnline(token, today.minusDays(1)).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors.appointmentDate").exists());
        sync();
        assertEquals(1, count("select count(*) from appointment where customer_id = ?", customerId(phone)));
    }

    /** Appointment Details show whether the examination fee is paid; it is not until someone pays. */
    @Test
    void apt04_detailsShowPaymentStatus() throws Exception {
        String token = customer(newPhone());
        Long id = book(token);
        mvc.perform(get("/api/my_appointments/" + id).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queueNumber").isNumber())
                .andExpect(jsonPath("$.paidAt").isEmpty());
        mvc.perform(get("/api/my_appointments/" + id + "/invoice").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invoice.status").value("unpaid"));
    }

    // ---------------------------------------------------------------- Walk-in Patient Registration
    @Test
    void wlk01_deskRegistersAWalkInCheckedInWithTheNextNumber() throws Exception {
        Long first = book(customer(newPhone()));
        String phone = newPhone();
        customer(phone);
        ResultActions r = mvc.perform(post("/api/appointments/walk-in").header("Authorization", bearer(desk))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"customerId":%d,"clinicId":"%s","reason":"Khám tổng quát","department":"Tim mạch","note":"Đến trực tiếp"}"""
                                .formatted(customerId(phone), CLINIC_1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.appointmentDate").value(today.toString()))
                .andExpect(jsonPath("$.checkedInAt").isNotEmpty())
                .andExpect(jsonPath("$.note").value("Đến trực tiếp"));
        Integer firstNo = jdbc.queryForObject("select queue_number from appointment where id = ?", Integer.class, first);
        assertTrue(Integer.parseInt(json(r, "$.queueNumber")) > firstNo);
    }

    @Test
    void wlk02_customerCannotUseTheDeskEndpoint() throws Exception {
        String token = customer(newPhone());
        mvc.perform(post("/api/appointments/walk-in").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"customerId":1,"clinicId":"%s","reason":"Khám","department":"Tim mạch"}""".formatted(CLINIC_1)))
                .andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- Payment (FE-23)
    /** Online Payment: right after booking the examination-fee bill exists and can be paid. */
    @Test
    void pay01_examFeeBillExistsRightAfterBooking() throws Exception {
        String token = customer(newPhone());
        Long id = book(token);
        mvc.perform(get("/api/my_appointments/" + id + "/invoice").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invoice.status").value("unpaid"))
                .andExpect(jsonPath("$.invoice.totalAmount").isNumber());
    }

    /** Clinic Payment Confirmation: the desk takes the fee of a checked-in visit, which then joins the queue. */
    @Test
    void pay02_deskConfirmsPaymentAndTheVisitJoinsTheQueue() throws Exception {
        String token = customer(newPhone());
        Long id = book(token);
        checkIn(id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("checked_in"));
        payAtDesk(id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("paid"));
        mvc.perform(get("/api/my_appointments/" + id).header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.status").value("ready_for_examination"))
                .andExpect(jsonPath("$.paidAt").isNotEmpty());
        sync();
        assertEquals(1, count("select count(*) from payment p join invoice i on i.id = p.invoice_id "
                + "where i.appointment_id = ? and p.payment_method = 'CASH'", id));
    }

    // ---------------------------------------------------------------- Appointment Check-in
    @Test
    void chk01_deskChecksInTodayAndThePatientIsActivated() throws Exception {
        String phone = newPhone();
        Long id = book(customer(phone));
        checkIn(id).andExpect(status().isOk()).andExpect(jsonPath("$.checkedInAt").isNotEmpty());
        sync();
        assertNotEquals("NEUTRAL", jdbc.queryForObject("select status from customer where id = ?", String.class, customerId(phone)));
        checkIn(id).andExpect(status().isConflict());
    }

    @Test
    void chk02_futureVisitCannotCheckIn_customerCannotCheckIn() throws Exception {
        String token = customer(newPhone());
        Long future = Long.valueOf(json(bookOnline(token, today.plusDays(1)).andExpect(status().isCreated()), "$.id"));
        checkIn(future).andExpect(status().isConflict());
        mvc.perform(post("/api/appointments/" + future + "/check-in").header("Authorization", bearer(token)))
                .andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- Daily Examination Queue
    /** BF-02: checked in but not paid = not yet in the examination queue. */
    @Test
    void que01_unpaidCheckedInIsNotQueued() throws Exception {
        String token = customer(newPhone());
        Long id = book(token);
        checkIn(id).andExpect(status().isOk());
        queueOf(token, id).andExpect(status().isOk())
                .andExpect(jsonPath("$.ahead").isEmpty())
                .andExpect(jsonPath("$.awaitingPayment").value(true));
    }

    /** BR-05: the queue is ordered by when a patient became eligible (checked in and paid), not by booking number. */
    @Test
    void que02_orderedByEligibilityNotByBookingNumber() throws Exception {
        String tokenB = customer(newPhone());
        Long b = book(tokenB);
        String tokenC = customer(newPhone());
        Long c = book(tokenC);
        checkIn(c).andExpect(status().isOk());
        payAtDesk(c).andExpect(status().isOk());
        checkIn(b).andExpect(status().isOk());
        payAtDesk(b).andExpect(status().isOk());
        Integer aheadOfC = Integer.valueOf(json(queueOf(tokenC, c).andExpect(status().isOk()), "$.ahead"));
        Integer aheadOfB = Integer.valueOf(json(queueOf(tokenB, b).andExpect(status().isOk()), "$.ahead"));
        assertTrue(aheadOfC < aheadOfB, "C became eligible first and must be ahead of B");
    }

    @Test
    void que03_notCheckedInHasNoPlaceInTheQueue() throws Exception {
        String token = customer(newPhone());
        Long id = book(token);
        queueOf(token, id).andExpect(status().isOk())
                .andExpect(jsonPath("$.ahead").isEmpty())
                .andExpect(jsonPath("$.awaitingPayment").value(false));
    }

    // ---------------------------------------------------------------- Create Clinical Encounter
    private ResultActions encounter(String customerPhone, Long appointmentId) throws Exception {
        String doctor = tokenFor("0900000011", "Staff@123");
        return mvc.perform(post("/api/clinical_encounters").header("Authorization", bearer(doctor))
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"customerId":%d,"appointmentId":%d,"chiefComplaint":"Đau đầu, sốt nhẹ 2 ngày"}"""
                        .formatted(customerId(customerPhone), appointmentId)));
    }

    @Test
    void enc01_encounterLinkedToTheCheckedInPaidAppointment() throws Exception {
        String phone = newPhone();
        Long id = book(customer(phone));
        checkIn(id).andExpect(status().isOk());
        payAtDesk(id).andExpect(status().isOk());
        encounter(phone, id).andExpect(status().isCreated())
                .andExpect(jsonPath("$.appointmentId").value(id))
                .andExpect(jsonPath("$.chiefComplaint").value("Đau đầu, sốt nhẹ 2 ngày"));
        sync();
        assertEquals(1, count("select count(*) from clinical_encounter where appointment_id = ?", id));
    }

    @Test
    void enc02_noEncounterBeforeCheckIn() throws Exception {
        String phone = newPhone();
        Long id = book(customer(phone));
        sync();
        jdbc.update("update customer set status = 'PATIENT' where id = ?", customerId(phone));
        encounter(phone, id).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors.appointmentId").value("The patient has not checked in for that appointment"));
    }

    /** BF-02: the encounter is opened after check-in AND payment. */
    @Test
    void enc03_noEncounterForAnUnpaidVisit() throws Exception {
        String phone = newPhone();
        Long id = book(customer(phone));
        checkIn(id).andExpect(status().isOk());
        encounter(phone, id).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors.appointmentId").value("The examination fee for that appointment is not paid yet"));
    }
}
