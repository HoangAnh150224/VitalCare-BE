package com.vn.vitalcare.care.medicalrecord.dto;

import com.vn.vitalcare.entity.MonitoringNote;
import java.time.Instant;
import java.util.Map;

/** A monitoring note with who wrote it, and whether the caller may change it — only its author may. */
public record MonitoringNoteResponse(
        Long id,
        String content,
        String author,
        Instant createdAt,
        Instant updatedAt,
        boolean editable) {

    /** @param names users.id to full name, for the authors */
    public static MonitoringNoteResponse from(MonitoringNote note, Map<Long, String> names, Long callerId) {
        Long author = note.getCreatedById();
        return new MonitoringNoteResponse(
                note.getId(),
                note.getContent(),
                author == null ? null : names.get(author),
                note.getCreatedAt(),
                note.getUpdatedAt(),
                author != null && author.equals(callerId));
    }
}
