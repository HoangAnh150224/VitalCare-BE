package com.vn.vitalcare.it;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * FE-02 IoMT Device Management end to end: register / update a device (DeviceController), monitoring order and
 * device assignment to a patient (MonitoringOrderController, CustomerCareController), device pairing from the
 * patient portal (MyDeviceController), and the device's own upload with its token (DeviceOperationsController,
 * DeviceDataController) down to the medical_device, device_assignment and vital_signs tables.
 */
class DeviceIT extends CommittedIntegrationTest {

    private String desk;
    private String doctor;

    @BeforeEach
    void staff() throws Exception {
        desk = tokenOf(DESK_1);
        doctor = tokenOf(DOCTOR_1);
    }

    private String code() {
        return "IT-" + newPhone().substring(3);
    }

    private Long registerDevice(String token, String code) throws Exception {
        return Long.valueOf(json(callJson(token, post("/api/devices"), """
                {"deviceCode":"%s","serialNumber":"SN-%s","deviceType":"WRIST_MONITOR","manufacturer":"VitalCare","model":"VC Band 2"}"""
                .formatted(code, code)).andExpect(status().isCreated()), "$.id"));
    }

    private void acceptedMonitoringOrder(Long customerId) throws Exception {
        callJson(doctor, post("/api/monitoring_orders"), """
                {"customerId":%d,"reason":"Theo dõi SpO2 sau viêm phổi","decision":"accepted","consentNote":"Đồng ý"}"""
                .formatted(customerId)).andExpect(status().is2xxSuccessful());
    }

    private Long assign(Long customerId, Long deviceId) throws Exception {
        return Long.valueOf(json(callJson(desk, post("/api/customers/" + customerId + "/devices"), """
                {"deviceId":%d,"note":"Giao tại quầy"}""".formatted(deviceId)).andExpect(status().is2xxSuccessful()), "$.id"));
    }

    @Test
    void dev01_deskRegistersADeviceAtItsClinic() throws Exception {
        String code = code();
        Long id = registerDevice(desk, code);
        call(desk, get("/api/devices/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceCode").value(code))
                .andExpect(jsonPath("$.status").value("available"))
                .andExpect(jsonPath("$.clinicId").value(CLINIC_1));
        assertEquals(1, count("select count(*) from medical_device where device_code = ? and clinic_id = ?::uuid "
                + "and status = 'AVAILABLE'", code, CLINIC_1));
    }

    @Test
    void dev02_missingFieldsAndDuplicateCodeAreRefused() throws Exception {
        int before = count("select count(*) from medical_device");
        callJson(desk, post("/api/devices"), """
                {"deviceCode":" ","serialNumber":"","deviceType":"","manufacturer":"","model":""}""")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors.deviceCode").value("Vui lòng nhập mã thiết bị."))
                .andExpect(jsonPath("$.errors.serialNumber").value("Vui lòng nhập số serial."));
        String code = code();
        registerDevice(desk, code);
        callJson(desk, post("/api/devices"), """
                {"deviceCode":"%s","serialNumber":"SN-OTHER-%s","deviceType":"WRIST_MONITOR","manufacturer":"VitalCare","model":"VC Band 2"}"""
                .formatted(code, code))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors.deviceCode").value("A device with that code already exists"));
        assertEquals(before + 1, count("select count(*) from medical_device"));
    }

    /** Row-level security: a device belongs to its clinic; the other clinic's desk neither lists nor opens it. */
    @Test
    void dev03_otherClinicDoesNotSeeTheDevice() throws Exception {
        String code = code();
        Long id = registerDevice(desk, code);
        String desk2 = tokenOf(DESK_2);
        call(desk2, get("/api/devices").param("q", code)).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].deviceCode", not(hasItem(code))));
        call(desk2, get("/api/devices/" + id)).andExpect(status().isNotFound());
        call(desk, get("/api/devices").param("q", code)).andExpect(jsonPath("$[*].deviceCode", hasItem(code)));
    }

    @Test
    void dev04_statusChangesOnlyWhileNobodyWearsIt() throws Exception {
        Long id = registerDevice(desk, code());
        callJson(desk, patch("/api/devices/" + id), "{\"status\":\"MAINTENANCE\",\"model\":\"VC Band 3\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("maintenance"))
                .andExpect(jsonPath("$.model").value("VC Band 3"));
        callJson(desk, patch("/api/devices/" + id), "{\"status\":\"AVAILABLE\"}").andExpect(status().isOk());
        Long patient = patient(newPhone());
        acceptedMonitoringOrder(patient);
        assign(patient, id);
        callJson(desk, patch("/api/devices/" + id), "{\"status\":\"MAINTENANCE\"}").andExpect(status().isConflict());
    }

    /** BF-04: a device is handed out only to a patient who accepted a monitoring order. */
    @Test
    void dev05_assignmentNeedsAnAcceptedMonitoringOrder() throws Exception {
        Long device = registerDevice(desk, code());
        Long patient = patient(newPhone());
        callJson(desk, post("/api/customers/" + patient + "/devices"), "{\"deviceId\":%d}".formatted(device))
                .andExpect(status().is4xxClientError());
        acceptedMonitoringOrder(patient);
        Long assignment = assign(patient, device);
        call(desk, get("/api/devices/" + device)).andExpect(jsonPath("$.status").value("assigned"))
                .andExpect(jsonPath("$.currentPatient.customerId").value(patient));
        call(desk, get("/api/devices/" + device + "/assignments")).andExpect(jsonPath("$[0].id").value(assignment));
        assertEquals(1, count("select count(*) from device_assignment where id = ? and customer_id = ? and status = 'ACTIVE'",
                assignment, patient));
    }

    @Test
    void dev06_returnedDeviceIsAvailableAgainAndTheAssignmentIsHistory() throws Exception {
        Long device = registerDevice(desk, code());
        Long patient = patient(newPhone());
        acceptedMonitoringOrder(patient);
        Long assignment = assign(patient, device);
        callJson(desk, post("/api/customers/" + patient + "/devices/" + assignment + "/return"), "{\"note\":\"Trả máy\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ended"));
        call(desk, get("/api/devices/" + device)).andExpect(jsonPath("$.status").value("available"));
        assertEquals(1, count("select count(*) from device_assignment where id = ? and status = 'ENDED' "
                + "and unassigned_at is not null", assignment));
    }

    /** Device Pairing (mobile app): the patient pairs the band they wear with their account, then unpairs it. */
    @Test
    void dev07_patientPairsAndUnpairsTheBandTheyWear() throws Exception {
        String code = code();
        Long device = registerDevice(desk, code);
        String phone = newPhone();
        Long patient = patient(phone);
        acceptedMonitoringOrder(patient);
        assign(patient, device);
        String own = tokenFor(phone, "Passw0rd!");
        call(own, get("/api/my_devices")).andExpect(status().isOk()).andExpect(jsonPath("$[*].deviceCode", hasItem(code)));
        callJson(own, post("/api/my_devices/pair"), "{\"deviceCode\":\"%s\"}".formatted(code.toLowerCase()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pairedAt").isNotEmpty());
        call(own, delete("/api/my_devices/" + code + "/pair")).andExpect(status().isOk())
                .andExpect(jsonPath("$.pairedAt").isEmpty());
        String stranger = tokenFor("0900000501", "Staff@123");
        callJson(stranger, post("/api/my_devices/pair"), "{\"deviceCode\":\"%s\"}".formatted(code))
                .andExpect(status().is4xxClientError());
    }

    /** The band uploads with its own token; readings land in vital_signs for the patient wearing it. */
    @Test
    void dev08_deviceUploadsWithItsTokenAndReadingsReachThePatient() throws Exception {
        String code = code();
        Long device = registerDevice(desk, code);
        Long patient = patient(newPhone());
        acceptedMonitoringOrder(patient);
        assign(patient, device);
        String token = json(call(desk, post("/api/devices/" + device + "/token")).andExpect(status().isOk()), "$.token");
        String at = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(1).truncatedTo(ChronoUnit.SECONDS).toString();
        String body = """
                {"clientBatchId":"b-1","battery":80,"readings":[
                 {"clientRecordId":"r-1","measuredAt":"%s","spo2":97,"heartRate":76,"skinTemperature":36.4}]}""".formatted(at);
        mvc.perform(post("/api/device_data/batch").header("X-Device-Code", code).header("X-Device-Token", "wrong")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/device_data/batch").header("X-Device-Code", code).header("X-Device-Token", token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(1));
        assertEquals(1, count("select count(*) from vital_signs where customer_id = ? and device_id = ? and heart_rate = 76",
                patient, device));
        assertEquals(1, count("select count(*) from medical_device where id = ? and last_seen_at is not null", device));
    }

    @Test
    void dev09_customerCannotManageDevices_noTokenIs401() throws Exception {
        String customer = customerWithProfile(newPhone());
        call(customer, get("/api/devices")).andExpect(status().isForbidden());
        callJson(customer, post("/api/devices"), """
                {"deviceCode":"%s","serialNumber":"SN","deviceType":"WRIST_MONITOR","manufacturer":"X","model":"Y"}""".formatted(code()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/devices")).andExpect(status().isUnauthorized());
    }
}
