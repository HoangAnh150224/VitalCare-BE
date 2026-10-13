package com.vn.vitalcare.care.schedule.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.vn.vitalcare.entity.DoctorSchedule;
import com.vn.vitalcare.entity.ScheduleStatus;
import com.vn.vitalcare.identity.rowlevel.service.RowLevelSecurity;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import java.util.UUID;

/** One period of a doctor's schedule as the API returns it. */
public record DoctorScheduleResponse(
        Long id,
        DoctorSummary doctor,
        UUID clinicId,
        LocalDate workDate,
        LocalTime startTime,
        LocalTime endTime,
        ScheduleStatus status,
        String note,
        /** What the caller may do to this row; see {@code DeviceResponse}. */
        @JsonProperty("_can")
        Map<String, Boolean> can) {

    public record DoctorSummary(Long id, String employeeCode, String fullName, String specialty) {
    }

    /** @param changeable false for a doctor's view of a colleague's period: readable, not theirs to change */
    public static DoctorScheduleResponse from(DoctorSchedule schedule, RowLevelSecurity rowLevel,
                                              boolean changeable) {
        var doctor = schedule.getEmployee();
        return new DoctorScheduleResponse(
                schedule.getId(),
                new DoctorSummary(doctor.getId(), doctor.getEmployeeCode(), doctor.getUser().getFullName(),
                        doctor.getSpecialty()),
                schedule.getClinicId(),
                schedule.getWorkDate(),
                schedule.getStartTime(),
                schedule.getEndTime(),
                schedule.getStatus(),
                schedule.getNote(),
                !changeable ? Map.of("write", false, "delete", false)
                        : rowLevel == null ? Map.of() : rowLevel.capabilities(schedule));
    }
}
