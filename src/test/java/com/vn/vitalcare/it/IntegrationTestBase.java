package com.vn.vitalcare.it;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/**
 * The whole application against a real PostgreSQL database (vitalcare_it, schema and seed by Liquibase):
 * security filters, controllers, services, repositories and SQL. Every test rolls back.
 */
@SpringBootTest
@Transactional
@org.springframework.context.annotation.Import(TextedCodes.Gateway.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:5432/vitalcare_it",
        "app.storage.dir=target/it-uploads",
        // Every test here comes from 127.0.0.1; the per-address limit is tested on its own with distinct addresses.
        "app.otp.max-per-ip-per-hour=100000"})
public abstract class IntegrationTestBase {

    /** The seeded operating clinic. */
    protected static final String CLINIC_1 = "5b1f8c2e-6a3d-4f7e-9c41-2d8e0a7b3c19";
    /** The second seeded clinic (VitalCare Đà Nẵng). */
    protected static final String CLINIC_2 = "c2a7e4d1-3b8f-4c62-9a15-7e0d3f9b2a48";

    // Seed accounts (Liquibase 002, 017, 019, 034, 050): phone and development password.
    protected static final String[] ADMIN = {"0900000001", "Admin@123"};
    protected static final String[] DESK_1 = {"0900000002", "Manager@123"};
    protected static final String[] DOCTOR_1 = {"0900000011", "Staff@123"};
    protected static final String[] DESK_2 = {"0900000021", "Staff@123"};
    protected static final String[] DOCTOR_2 = {"0900000022", "Staff@123"};
    protected static final String[] CLINIC_ADMIN_1 = {"0900000031", "VitalCareTest#2026"};
    protected static final String[] CAREGIVER = {"0900000041", "VitalCareTest#2026"};
    protected static final String[] DEMO_PATIENT = {"0900000501", "Staff@123"};

    protected String tokenOf(String[] account) throws Exception {
        return tokenFor(account[0], account[1]);
    }

    /** The customer record of an account. */
    protected Long customerId(String phone) {
        if (inTransaction()) {
            em.flush();
        }
        return jdbc.queryForObject("select c.id from customer c join users u on u.id = c.user_id where u.phone = ? "
                + "and c.guardian_id is null", Long.class, intl(phone));
    }

    /** Registers a customer and fills in what booking and activation need (birth date, gender, ID number, address). */
    protected String customerWithProfile(String phone) throws Exception {
        String token = register(phone, "Benh Nhan", "Passw0rd!");
        sync();
        jdbc.update("update customer set date_of_birth = '1990-05-01', gender = 'female', national_id = ?, "
                + "national_id_type = 'cccd', province = 'Hà Nội', ward = 'Phường Hàng Bài', address = '1 Tràng Tiền' "
                + "where user_id = (select id from users where phone = ?)", "00" + phone.substring(phone.length() - 10), intl(phone));
        return token;
    }

    /** A customer the clinic-1 desk has activated as a patient through the API; answers the customer id. */
    protected Long patient(String phone) throws Exception {
        customerWithProfile(phone);
        Long id = customerId(phone);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/customers/" + id + "/activate")
                .header("Authorization", bearer(tokenOf(DESK_1)))).andExpect(status().isOk());
        sync();
        return id;
    }

    /** A clinic-1 patient wearing a band of their own: the doctor's accepted monitoring order, the device, its token. */
    protected record Monitored(String phone, Long customerId, Long deviceId, String deviceCode, String deviceToken) {
    }

    protected Monitored monitoredPatient() throws Exception {
        String phone = newPhone();
        Long customer = patient(phone);
        String doctor = tokenOf(DOCTOR_1);
        String desk = tokenOf(DESK_1);
        callJson(doctor, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/monitoring_orders"), """
                {"customerId":%d,"reason":"Theo dõi sau xuất viện","decision":"accepted"}""".formatted(customer))
                .andExpect(status().is2xxSuccessful());
        String code = "IT-" + phone.substring(3);
        Long device = Long.valueOf(json(callJson(desk, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/devices"), """
                {"deviceCode":"%s","serialNumber":"SN-%s","deviceType":"WRIST_MONITOR","manufacturer":"VitalCare","model":"VC Band 2"}"""
                .formatted(code, code)).andExpect(status().isCreated()), "$.id"));
        callJson(desk, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/customers/" + customer + "/devices"),
                "{\"deviceId\":%d}".formatted(device)).andExpect(status().is2xxSuccessful());
        String token = json(call(desk, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/devices/" + device + "/token"))
                .andExpect(status().isOk()), "$.token");
        return new Monitored(phone, customer, device, code, token);
    }

    /** A doctor of clinic 1 created for the test, working every day from 00:00 to 23:55, and their token. */
    protected record Doctor(Long id, String phone, String token) {
    }

    protected Doctor freshDoctor() throws Exception {
        String phone = newPhone();
        String clinicAdmin = tokenOf(CLINIC_ADMIN_1);
        Long id = Long.valueOf(json(callJson(clinicAdmin, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/employees"), """
                {"phone":"%s","fullName":"Bác Sĩ Kiểm Thử","password":"Passw0rd!","staffType":"doctor","specialty":"Tim mạch",
                 "professionalTitle":"Bác sĩ","clinicId":"%s"}""".formatted(phone, CLINIC_1)).andExpect(status().isCreated()), "$.id"));
        StringBuilder week = new StringBuilder();
        for (int day = 1; day <= 7; day++) {
            week.append(day > 1 ? "," : "").append("{\"dayOfWeek\":%d,\"startTime\":\"00:00\",\"endTime\":\"23:55\",\"visitMinutes\":5}".formatted(day));
        }
        callJson(clinicAdmin, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/doctors/" + id + "/working-hours"),
                "{\"sessions\":[" + week + "]}").andExpect(status().isOk());
        return new Doctor(id, phone, tokenFor(phone, "Passw0rd!"));
    }

    protected static final java.time.LocalDate TODAY = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh"));

    /** The patient books today at clinic 1 and the desk checks them in and takes the fee: in the doctor's queue. */
    protected Long paidVisit(String patientToken) throws Exception {
        Long visit = Long.valueOf(json(callJson(patientToken, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/my_appointments"), """
                {"clinicId":"%s","appointmentDate":"%s","reason":"Khám định kỳ","department":"Tim mạch"}""".formatted(CLINIC_1, TODAY))
                .andExpect(status().isCreated()), "$.id"));
        String desk = tokenOf(DESK_1);
        call(desk, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/appointments/" + visit + "/check-in"))
                .andExpect(status().isOk());
        String invoice = json(call(desk, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/invoices/exam")
                .param("appointmentId", visit.toString())).andExpect(status().isOk()), "$.id");
        callJson(desk, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/invoices/" + invoice + "/payments"),
                "{\"method\":\"CASH\"}").andExpect(status().isOk());
        return visit;
    }

    /** The doctor opens the encounter of a paid visit with a diagnosis; answers the encounter id. */
    protected Long encounter(Doctor doctor, Long customerId, Long visit) throws Exception {
        return Long.valueOf(json(callJson(doctor.token(), org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/clinical_encounters"), """
                {"customerId":%d,"appointmentId":%d,"chiefComplaint":"Hồi hộp, khó thở khi gắng sức","diagnosis":"Tăng huyết áp độ 1"}"""
                .formatted(customerId, visit)).andExpect(status().isCreated()), "$.id"));
    }

    /** A whole visit: booked, checked in, paid, examined and concluded by the doctor. Answers the visit id. */
    protected Long completedVisit(String patientPhone, Doctor doctor) throws Exception {
        Long visit = paidVisit(tokenFor(patientPhone, "Passw0rd!"));
        Long enc = encounter(doctor, customerId(patientPhone), visit);
        call(doctor.token(), org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/clinical_encounters/" + enc + "/complete"))
                .andExpect(status().isOk());
        return visit;
    }

    /** A moment after the device was handed out (readings before it belong to nobody), within the 5-minute clock skew. */
    protected static String soon(int secondsAhead) {
        return java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).plusSeconds(30 + secondsAhead)
                .truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
    }

    /** The band's own upload with its token: readings as JSON objects (measuredAt, spo2, heartRate, …). */
    protected ResultActions upload(Monitored m, String... readings) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/device_data/batch")
                .header("X-Device-Code", m.deviceCode()).header("X-Device-Token", m.deviceToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"readings\":[" + String.join(",", readings) + "]}"));
    }

    protected org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder as(String token,
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) {
        return request.header("Authorization", bearer(token));
    }

    protected ResultActions call(String token, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        return mvc.perform(request.header("Authorization", bearer(token)));
    }

    protected ResultActions callJson(String token, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                                     String body) throws Exception {
        return mvc.perform(request.header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 5_000_000));

    @Autowired
    private WebApplicationContext context;

    @Autowired
    protected JdbcTemplate jdbc;

    protected MockMvc mvc;

    @BeforeEach
    void buildMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    /** A Vietnamese mobile number no seed or earlier run uses: 097 + 7 digits. */
    protected static String newPhone() {
        return "097" + String.format("%07d", SEQ.incrementAndGet() % 10_000_000);
    }

    protected static String intl(String local) {
        return "+84" + local.substring(1);
    }

    protected ResultActions postJson(String url, String json) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    protected static String json(ResultActions result, String path) throws Exception {
        Object value = JsonPath.read(result.andReturn().getResponse().getContentAsString(), path);
        return value == null ? null : value.toString();
    }

    @Autowired
    protected TextedCodes texted;

    /** Asks for a registration code by SMS and answers the code the (captured) SMS carried. */
    protected String registrationCode(String phone) throws Exception {
        postJson("/api/auth/register/otp", """
                {"phone":"%s"}""".formatted(phone)).andExpect(status().isOk());
        return texted.last(phone);
    }

    /** The whole registration through the API (code by SMS, then the account); answers the response. */
    protected ResultActions registerWith(String phone, String fullName, String password) throws Exception {
        String code = registrationCode(phone);
        return postJson("/api/auth/register", """
                {"phone":"%s","otp":"%s","fullName":"%s","password":"%s"}""".formatted(phone, code, fullName, password));
    }

    /** Registers through the API and answers the access token. */
    protected String register(String phone, String fullName, String password) throws Exception {
        return json(registerWith(phone, fullName, password).andExpect(status().isOk()), "$.accessToken");
    }

    /** Signs in through the API and answers the access token. */
    protected String tokenFor(String phone, String password) throws Exception {
        return json(postJson("/api/auth/login", """
                {"phone":"%s","password":"%s"}""".formatted(phone, password)).andExpect(status().isOk()), "$.accessToken");
    }

    protected static String bearer(String token) {
        return "Bearer " + token;
    }

    @jakarta.persistence.PersistenceContext
    protected jakarta.persistence.EntityManager em;

    /** Writes what JPA holds so SQL sees it, and forgets it so the next read comes from the table. */
    protected void sync() {
        if (inTransaction()) {
            em.flush();
            em.clear();
        }
    }

    /** Suites whose subject runs after commit (alerts, notifications) run without the rollback transaction. */
    protected static boolean inTransaction() {
        return org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive();
    }

    protected Integer count(String sql, Object... args) {
        if (inTransaction()) {
            em.flush();
        }
        return jdbc.queryForObject(sql, Integer.class, args);
    }
}
