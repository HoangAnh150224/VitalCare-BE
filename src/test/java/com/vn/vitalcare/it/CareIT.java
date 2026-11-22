package com.vn.vitalcare.it;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * FE-08 Medical Records and FE-09 Monitoring Assignment end to end: the patient's medical record with optimistic
 * locking (MedicalRecordController), monitoring notes and their author (MonitoringNoteController), the care team
 * (CustomerCareController) and what a doctor sees as their patients (MyPatientsController), and monitoring orders
 * with the patient's consent (MonitoringOrderController).
 */
class CareIT extends CommittedIntegrationTest {

    private String doctor;
    private String clinicAdmin;

    @BeforeEach
    void staff() throws Exception {
        doctor = tokenOf(DOCTOR_1);
        clinicAdmin = tokenOf(CLINIC_ADMIN_1);
    }

    private String record(String bloodType, String allergies, Integer version) {
        return """
                {"bloodType":"%s","allergies":"%s","medicalHistory":"Tăng huyết áp 5 năm","currentMedications":"Amlodipin 5mg",
                 "version":%s}""".formatted(bloodType, allergies, version == null ? "null" : version);
    }

    @Test
    void car01_doctorWritesTheMedicalRecordAndReadsItBack() throws Exception {
        Long patient = patient(newPhone());
        callJson(doctor, put("/api/customers/" + patient + "/medical-record"), record("O+", "Penicillin", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bloodType").value("O+"));
        call(doctor, get("/api/customers/" + patient + "/medical-record")).andExpect(status().isOk())
                .andExpect(jsonPath("$.allergies").value("Penicillin"))
                .andExpect(jsonPath("$.currentMedications").value("Amlodipin 5mg"));
        assertEquals(1, count("select count(*) from medical_record where customer_id = ?", patient));
    }

    /** Two people editing the record: the save over a newer version is refused, nothing is overwritten. */
    @Test
    void car02_staleVersionIsRefused() throws Exception {
        Long patient = patient(newPhone());
        Integer v = Integer.valueOf(json(callJson(doctor, put("/api/customers/" + patient + "/medical-record"),
                record("A+", "Không", null)).andExpect(status().isOk()), "$.version"));
        callJson(doctor, put("/api/customers/" + patient + "/medical-record"), record("A+", "Hải sản", v)).andExpect(status().isOk());
        callJson(doctor, put("/api/customers/" + patient + "/medical-record"), record("B+", "Phấn hoa", v)).andExpect(status().isConflict());
        assertEquals("Hải sản", jdbc.queryForObject("select allergies from medical_record where customer_id = ?", String.class, patient));
    }

    @Test
    void car03_onlyAPatientHasAMedicalRecord() throws Exception {
        String phone = newPhone();
        customerWithProfile(phone);
        callJson(doctor, put("/api/customers/" + customerId(phone) + "/medical-record"), record("O+", "Không", null))
                .andExpect(status().is4xxClientError());
        assertEquals(0, count("select count(*) from medical_record where customer_id = ?", customerId(phone)));
    }

    /** Monitoring notes: written by a doctor, changed only by their author. */
    @Test
    void car04_monitoringNoteIsChangedOnlyByItsAuthor() throws Exception {
        Long patient = patient(newPhone());
        Long note = Long.valueOf(json(callJson(doctor, post("/api/customers/" + patient + "/monitoring-notes"),
                "{\"content\":\"SpO2 ổn định 96-98%, tiếp tục theo dõi\"}").andExpect(status().is2xxSuccessful()), "$.id"));
        callJson(doctor, patch("/api/customers/" + patient + "/monitoring-notes/" + note), "{\"content\":\"SpO2 ổn định, giảm tần suất\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").value("SpO2 ổn định, giảm tần suất"));
        Doctor other = freshDoctor();
        callJson(other.token(), patch("/api/customers/" + patient + "/monitoring-notes/" + note), "{\"content\":\"Sửa của người khác\"}")
                .andExpect(status().isForbidden());
        callJson(doctor, post("/api/customers/" + patient + "/monitoring-notes"), "{\"content\":\"   \"}")
                .andExpect(status().isUnprocessableContent());
        call(doctor, get("/api/customers/" + patient + "/monitoring-notes")).andExpect(jsonPath("$[*].content", hasItem("SpO2 ổn định, giảm tần suất")));
    }

    /** The clinic administrator puts a doctor in the patient's care team; the patient then is one of that doctor's. */
    @Test
    void car05_careTeamDecidesWhoseMyPatientsListShowsThePatient() throws Exception {
        Long patient = patient(newPhone());
        Doctor assigned = freshDoctor();
        Doctor outsider = freshDoctor();
        callJson(clinicAdmin, post("/api/customers/" + patient + "/care-team"), "{\"employeeId\":%d,\"note\":\"Bác sĩ phụ trách\"}"
                .formatted(assigned.id())).andExpect(status().is2xxSuccessful());
        call(assigned.token(), get("/api/my_patients")).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(patient.intValue())));
        call(assigned.token(), get("/api/my_patients/" + patient)).andExpect(status().isOk());
        call(outsider.token(), get("/api/my_patients")).andExpect(jsonPath("$[*].id", not(hasItem(patient.intValue()))));
        call(outsider.token(), get("/api/my_patients/" + patient)).andExpect(status().isNotFound());
    }

    @Test
    void car06_receptionistNotInACareTeam_andNoDuplicate() throws Exception {
        Long patient = patient(newPhone());
        Long receptionist = jdbc.queryForObject("select e.id from employee e join users u on u.id = e.user_id where u.phone = '+84900000002'", Long.class);
        callJson(clinicAdmin, post("/api/customers/" + patient + "/care-team"), "{\"employeeId\":%d}".formatted(receptionist))
                .andExpect(status().is4xxClientError());
        Doctor d = freshDoctor();
        callJson(clinicAdmin, post("/api/customers/" + patient + "/care-team"), "{\"employeeId\":%d}".formatted(d.id()))
                .andExpect(status().is2xxSuccessful());
        callJson(clinicAdmin, post("/api/customers/" + patient + "/care-team"), "{\"employeeId\":%d}".formatted(d.id()))
                .andExpect(status().isConflict());
        assertEquals(1, count("select count(*) from monitoring_assignment where customer_id = ? and status = 'ACTIVE'", patient));
    }

    @Test
    void car07_endedAssignmentIsHistoryAndLeavesTheDoctorsList() throws Exception {
        Long patient = patient(newPhone());
        Doctor d = freshDoctor();
        Long assignment = Long.valueOf(json(callJson(clinicAdmin, post("/api/customers/" + patient + "/care-team"),
                "{\"employeeId\":%d}".formatted(d.id())).andExpect(status().is2xxSuccessful()), "$.id"));
        callJson(clinicAdmin, post("/api/customers/" + patient + "/care-team/" + assignment + "/end"), "{\"note\":\"Chuyển bác sĩ\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ended"));
        call(d.token(), get("/api/my_patients")).andExpect(jsonPath("$[*].id", not(hasItem(patient.intValue()))));
        call(clinicAdmin, get("/api/customers/" + patient + "/care-team")).andExpect(jsonPath("$[*].id", not(hasItem(assignment.intValue()))));
        assertEquals(1, count("select count(*) from monitoring_assignment where id = ? and status = 'ENDED' and unassigned_at is not null", assignment));
    }

    /** BF-04: the patient's decision on monitoring is recorded; one accepted order in force at a time. */
    @Test
    void car08_monitoringOrderRecordsTheDecision() throws Exception {
        Long patient = patient(newPhone());
        callJson(doctor, post("/api/monitoring_orders"), """
                {"customerId":%d,"reason":"Theo dõi nhịp tim","decision":"declined","consentNote":"Chưa muốn đeo vòng"}""".formatted(patient))
                .andExpect(status().is2xxSuccessful());
        Long order = Long.valueOf(json(callJson(doctor, post("/api/monitoring_orders"), """
                {"customerId":%d,"reason":"Theo dõi nhịp tim","decision":"accepted"}""".formatted(patient))
                .andExpect(status().is2xxSuccessful()), "$.id"));
        callJson(doctor, post("/api/monitoring_orders"), """
                {"customerId":%d,"reason":"Lần hai","decision":"accepted"}""".formatted(patient)).andExpect(status().isConflict());
        call(doctor, get("/api/monitoring_orders").param("customerId", patient.toString())).andExpect(jsonPath("$.length()").value(2));
        callJson(doctor, post("/api/monitoring_orders/" + order + "/end"), "{\"note\":\"Đã ổn định\"}").andExpect(status().isOk());
        assertEquals(1, count("select count(*) from monitoring_order where id = ? and status = 'ENDED' and ended_at is not null", order));
    }

    /**
     * A clinic serves the customers who booked there (plus those who have not booked anywhere yet): once the patient
     * has a visit at clinic 1, clinic 2's doctor does not reach their record, and another patient never does.
     */
    @Test
    void car09_otherClinicAndOtherPatientCannotReadTheRecord() throws Exception {
        freshDoctor();
        String phone = newPhone();
        Long patient = patient(phone);
        paidVisit(tokenFor(phone, "Passw0rd!"));
        callJson(doctor, put("/api/customers/" + patient + "/medical-record"), record("O+", "Không", null)).andExpect(status().isOk());
        String stranger = tokenOf(DEMO_PATIENT);
        call(stranger, get("/api/customers/" + patient + "/medical-record")).andExpect(status().isForbidden());
        call(tokenOf(DOCTOR_2), get("/api/customers/" + patient + "/medical-record")).andExpect(status().isNotFound());
    }
}
