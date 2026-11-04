package com.vn.vitalcare.care.examroom.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.vn.vitalcare.entity.ExamRoom;
import com.vn.vitalcare.entity.ExamRoomStatus;
import com.vn.vitalcare.identity.rowlevel.service.RowLevelSecurity;
import java.util.Map;
import java.util.UUID;

/** A room as the API returns it. Who is in it is the board's to say; see {@link RoomBoardResponse}. */
public record ExamRoomResponse(
        Long id,
        ClinicSummary clinic,
        String code,
        String name,
        ExamRoomStatus status,
        /** What the caller may do to this row; see {@code DeviceResponse}. */
        @JsonProperty("_can")
        Map<String, Boolean> can) {

    public record ClinicSummary(UUID id, String name) {
    }

    public static ExamRoomResponse from(ExamRoom room, RowLevelSecurity rowLevel) {
        return new ExamRoomResponse(
                room.getId(),
                new ClinicSummary(room.getClinicId(), room.getClinic().getClinicName()),
                room.getCode(),
                room.getName(),
                room.getStatus(),
                rowLevel == null ? Map.of() : rowLevel.capabilities(room));
    }
}
