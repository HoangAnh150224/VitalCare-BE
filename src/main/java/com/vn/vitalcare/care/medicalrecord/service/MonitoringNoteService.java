package com.vn.vitalcare.care.medicalrecord.service;

import com.vn.vitalcare.care.medicalrecord.dto.MonitoringNoteRequest;
import com.vn.vitalcare.care.medicalrecord.dto.MonitoringNoteResponse;
import java.util.List;

/**
 * Notes staff keep while following a patient (SRS 3.9.3): each one signed by
 * whoever wrote it, newest first, and changed afterwards by its author alone.
 * A patient the caller's clinic cannot see is not found, and so are their notes.
 */
public interface MonitoringNoteService {

    List<MonitoringNoteResponse> list(Long customerId);

    MonitoringNoteResponse add(Long customerId, MonitoringNoteRequest request);

    /** Refused for anybody but the note's author. */
    MonitoringNoteResponse update(Long customerId, Long noteId, MonitoringNoteRequest request);
}
