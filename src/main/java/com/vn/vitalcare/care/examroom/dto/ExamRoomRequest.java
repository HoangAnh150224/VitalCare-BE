package com.vn.vitalcare.care.examroom.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * {@code POST|PATCH /exam_rooms}: a room of a clinic. The clinic is fixed at
 * creation; on an update it is ignored.
 */
public record ExamRoomRequest(
        UUID clinicId,

        @NotBlank(message = "A room code is required")
        @Size(max = 20, message = "The room code must be at most 20 characters")
        String code,

        @Size(max = 100, message = "The room name must be at most 100 characters")
        String name) {
}
