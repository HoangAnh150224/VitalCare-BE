package com.vn.vitalcare.it;

import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * FE-10 Doctor Schedule and Daily Examination Queue end to end: a doctor's weekly working hours
 * (DoctorController), blocked times (DoctorScheduleController) and what booking offers from them (open days), exam
 * rooms and the room board (ExamRoomController): a paid patient is dispatched to the room of the doctor on duty,
 * finished, skipped, and a paused room takes nobody.
 *
 * <p>The exam-room tests use the Hô hấp department with a room and a doctor created for the test, so the shared line
 * they dispatch from holds only this test's patient; today's leftover test visits of that line are set aside first.
 */
class ScheduleIT extends CommittedIntegrationTest {

    private String clinicAdmin;

    @BeforeEach
    void staff() throws Exception {
        clinicAdmin = tokenOf(CLINIC_ADMIN_1);
    }

    private String week(Long room) {
        StringBuilder week = new StringBuilder();
        for (int day = 1; day <= 7; day++) {
            week.append(day > 1 ? "," : "").append("{\"dayOfWeek\":%d,\"startTime\":\"00:00\",\"endTime\":\"23:55\",\"visitMinutes\":5%s}"
                    .formatted(day, room == null ? "" : ",\"examRoomId\":" + room));
        }
        return "{\"sessions\":[" + week + "]}";
    }

    @Test
    void sch01_clinicAdminSetsADoctorsWeekInDayOrder() throws Exception {
        Doctor d = freshDoctor();
        callJson(clinicAdmin, put("/api/doctors/" + d.id() + "/working-hours"), """
                {"sessions":[{"dayOfWeek":3,"startTime":"13:00","endTime":"17:00","visitMinutes":15},
                             {"dayOfWeek":1,"startTime":"08:00","endTime":"12:00","visitMinutes":15}]}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].dayOfWeek").value(1))
                .andExpect(jsonPath("$[1].dayOfWeek").value(3));
        call(d.token(), get("/api/doctors/" + d.id() + "/working-hours")).andExpect(jsonPath("$.length()").value(2));
        assertEquals(2, count("select count(*) from doctor_working_hours where employee_id = ?", d.id()));
    }

    @Test
    void sch02_overlappingOrBackwardSessionsAreRefusedAndTheWeekKept() throws Exception {
        Doctor d = freshDoctor();
        callJson(clinicAdmin, put("/api/doctors/" + d.id() + "/working-hours"), """
                {"sessions":[{"dayOfWeek":1,"startTime":"08:00","endTime":"12:00","visitMinutes":15},
                             {"dayOfWeek":1,"startTime":"11:00","endTime":"14:00","visitMinutes":15}]}""")
                .andExpect(status().is4xxClientError());
        callJson(clinicAdmin, put("/api/doctors/" + d.id() + "/working-hours"), """
                {"sessions":[{"dayOfWeek":2,"startTime":"12:00","endTime":"08:00","visitMinutes":15}]}""")
                .andExpect(status().is4xxClientError());
        assertEquals(7, count("select count(*) from doctor_working_hours where employee_id = ?", d.id()));
    }

    /** A doctor blocks part of a day; only their own blocks are theirs to change. */
    @Test
    void sch03_doctorBlocksTimeAndOnlyTheyChangeIt() throws Exception {
        Doctor d = freshDoctor();
        String day = TODAY.plusDays(2).toString();
        Long block = Long.valueOf(json(callJson(d.token(), post("/api/doctor_schedules"), """
                {"workDate":"%s","startTime":"09:00","endTime":"10:00","note":"Hội chẩn"}""".formatted(day))
                .andExpect(status().isCreated()), "$.id"));
        callJson(d.token(), post("/api/doctor_schedules"), """
                {"workDate":"%s","startTime":"11:00","endTime":"10:00"}""".formatted(day)).andExpect(status().is4xxClientError());
        Doctor other = freshDoctor();
        callJson(other.token(), patch("/api/doctor_schedules/" + block), """
                {"workDate":"%s","startTime":"09:00","endTime":"11:00"}""".formatted(day)).andExpect(status().isNotFound());
        call(d.token(), delete("/api/doctor_schedules/" + block)).andExpect(status().isOk());
        assertEquals(0, count("select count(*) from doctor_schedule where id = ? and deleted_at is null", block));
    }

    @Test
    void sch04_openDaysOfferDaysWithRemainingVisits() throws Exception {
        freshDoctor();
        String customer = customerWithProfile(newPhone());
        call(customer, get("/api/clinics/" + CLINIC_1 + "/open-days").param("days", "3")).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].date", hasItem(TODAY.toString())));
        call(customer, get("/api/clinics/" + CLINIC_1 + "/doctors").param("date", TODAY.toString())).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").isNumber());
    }

    @Test
    void sch05_clinicAdminCreatesExamRoom_duplicateCodeRefused() throws Exception {
        String code = "R" + newPhone().substring(5);
        callJson(clinicAdmin, post("/api/exam_rooms"), """
                {"clinicId":"%s","code":"%s","name":"Phòng khám thử","department":"Hô hấp"}""".formatted(CLINIC_1, code))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.code").value(code));
        callJson(clinicAdmin, post("/api/exam_rooms"), """
                {"clinicId":"%s","code":"%s","name":"Trùng"}""".formatted(CLINIC_1, code)).andExpect(status().is4xxClientError());
        callJson(tokenOf(DESK_1), post("/api/exam_rooms"), """
                {"clinicId":"%s","code":"X%s"}""".formatted(CLINIC_1, code)).andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- the room board
    private record Room(Long id, Doctor doctor) {
    }

    /** A Hô hấp room with a fresh doctor on duty in it all week; leftover test visits of today's Hô hấp line set aside. */
    private Room roomOnDuty() throws Exception {
        jdbc.update("update appointment set exam_room_id = null where status = 'NO_SHOW' and exam_room_id in "
                + "(select id from exam_room where name = 'Phòng hô hấp thử')");
        jdbc.update("update appointment set status = 'NO_SHOW' where appointment_date = current_date and department = 'Hô hấp' "
                + "and status in ('READY_FOR_EXAMINATION', 'CHECKED_IN', 'IN_EXAM') and customer_id in "
                + "(select c.id from customer c join users u on u.id = c.user_id where u.phone like '+8497%')");
        // Rooms of earlier runs of this test go off duty, so only this test's room can take the patient.
        jdbc.update("delete from doctor_working_hours where exam_room_id in (select id from exam_room where name = 'Phòng hô hấp thử')");
        String code = "R" + newPhone().substring(5);
        Long room = Long.valueOf(json(callJson(clinicAdmin, post("/api/exam_rooms"), """
                {"clinicId":"%s","code":"%s","name":"Phòng hô hấp thử","department":"Hô hấp"}""".formatted(CLINIC_1, code))
                .andExpect(status().isCreated()), "$.id"));
        Doctor d = freshDoctor();
        jdbc.update("update employee set specialty = 'Hô hấp' where id = ?", d.id());
        callJson(clinicAdmin, put("/api/doctors/" + d.id() + "/working-hours"), week(room)).andExpect(status().isOk());
        return new Room(room, d);
    }

    /** A patient of the Hô hấp line, checked in and paid today. */
    private Long readyPatient(String phone) throws Exception {
        patient(phone);
        String own = tokenFor(phone, "Passw0rd!");
        Long visit = Long.valueOf(json(callJson(own, post("/api/my_appointments"), """
                {"clinicId":"%s","appointmentDate":"%s","reason":"Ho kéo dài","department":"Hô hấp"}""".formatted(CLINIC_1, TODAY))
                .andExpect(status().isCreated()), "$.id"));
        String desk = tokenOf(DESK_1);
        call(desk, post("/api/appointments/" + visit + "/check-in")).andExpect(status().isOk());
        String invoice = json(call(desk, get("/api/invoices/exam").param("appointmentId", visit.toString())), "$.id");
        callJson(desk, post("/api/invoices/" + invoice + "/payments"), "{\"method\":\"CASH\"}").andExpect(status().isOk());
        return visit;
    }

    /** The visit in the room: dispatched by itself when the room is free (AutoDispatch), else on Gọi tiếp. */
    private void inRoom(Room room, Long visit) throws Exception {
        for (int i = 0; i < 20 && count("select count(*) from appointment where id = ? and status = 'IN_EXAM'", visit) == 0; i++) {
            Thread.sleep(250);
        }
        if (count("select count(*) from appointment where id = ? and status = 'IN_EXAM'", visit) == 0) {
            call(room.doctor().token(), post("/api/exam_rooms/" + room.id() + "/call-next")).andExpect(status().isOk());
        }
        assertEquals(1, count("select count(*) from appointment where id = ? and status = 'IN_EXAM' and exam_room_id = ?", visit, room.id()));
    }

    @Test
    void sch06_paidPatientGoesIntoTheRoomOfTheDoctorOnDuty() throws Exception {
        Room room = roomOnDuty();
        Long visit = readyPatient(newPhone());
        inRoom(room, visit);
        call(room.doctor().token(), get("/api/exam_rooms/mine")).andExpect(status().isOk())
                .andExpect(jsonPath("$.rooms[0].current.appointmentId").value(visit));
        call(tokenOf(DESK_1), get("/api/exam_rooms/board")).andExpect(status().isOk())
                .andExpect(jsonPath("$.rooms[*].id", hasItem(room.id().intValue())));
    }

    @Test
    void sch07_finishedVisitLeavesTheRoomFree() throws Exception {
        Room room = roomOnDuty();
        Long visit = readyPatient(newPhone());
        inRoom(room, visit);
        call(room.doctor().token(), post("/api/exam_rooms/" + room.id() + "/finish").param("appointmentId", visit.toString()))
                .andExpect(status().isOk());
        assertEquals(0, count("select count(*) from appointment where exam_room_id = ? and status = 'IN_EXAM'", room.id()));
        assertTrue(count("select count(*) from appointment where id = ? and status in ('COMPLETED', 'EXAMINED', 'AWAY')", visit) == 1
                || count("select count(*) from appointment where id = ? and status <> 'IN_EXAM'", visit) == 1);
    }

    /** A called patient who does not come in is marked a no-show and the room is free again. */
    @Test
    void sch08_calledPatientWhoDoesNotComeIsANoShow() throws Exception {
        Room room = roomOnDuty();
        Long visit = readyPatient(newPhone());
        inRoom(room, visit);
        call(room.doctor().token(), post("/api/exam_rooms/" + room.id() + "/no-show").param("appointmentId", visit.toString()))
                .andExpect(status().isOk());
        assertEquals(1, count("select count(*) from appointment where id = ? and status = 'NO_SHOW'", visit));
        assertEquals(0, count("select count(*) from appointment where exam_room_id = ? and status = 'IN_EXAM'", room.id()));
    }

    @Test
    void sch09_pausedRoomTakesNobody() throws Exception {
        Room room = roomOnDuty();
        call(room.doctor().token(), post("/api/exam_rooms/" + room.id() + "/pause")).andExpect(status().isOk());
        call(room.doctor().token(), post("/api/exam_rooms/" + room.id() + "/call-next")).andExpect(status().isConflict());
        call(room.doctor().token(), post("/api/exam_rooms/" + room.id() + "/resume")).andExpect(status().isOk());
        assertEquals(0, count("select count(*) from exam_room where id = ? and status = 'PAUSED'", room.id()));
    }

    /** A called patient who has stepped out is passed over: back to the queue, the room is free; a stale board is refused. */
    @Test
    void sch10_passOverSendsThePatientBackToTheQueue() throws Exception {
        Room room = roomOnDuty();
        Long visit = readyPatient(newPhone());
        inRoom(room, visit);
        String doctor = room.doctor().token();
        // Paused so the free room does not take the patient straight back in (AutoDispatch).
        call(doctor, post("/api/exam_rooms/" + room.id() + "/pause")).andExpect(status().isOk());
        call(doctor, post("/api/exam_rooms/" + room.id() + "/pass-over").param("appointmentId", "999999999"))
                .andExpect(status().isConflict());
        call(doctor, post("/api/exam_rooms/" + room.id() + "/pass-over").param("appointmentId", visit.toString()))
                .andExpect(status().isOk());
        assertEquals(1, count("select count(*) from appointment where id = ? and status = 'READY_FOR_EXAMINATION' "
                + "and exam_room_id is null and called_at is null and skipped_at is not null", visit));
        call(doctor, post("/api/exam_rooms/" + room.id() + "/pass-over")).andExpect(status().isConflict());
        call(doctor, post("/api/exam_rooms/" + room.id() + "/resume")).andExpect(status().isOk());
    }
}
