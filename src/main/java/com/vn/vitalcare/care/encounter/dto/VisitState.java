package com.vn.vitalcare.care.encounter.dto;

import com.vn.vitalcare.care.appointment.service.VisitReturns;
import com.vn.vitalcare.entity.AppointmentStatus;
import java.util.List;

/**
 * The visit an encounter documents, as the doctor works it: where it stands
 * (in the room, out for services or another doctor, back in line) and what
 * it went out for. Null status for an encounter recorded without a visit.
 */
public record VisitState(Long appointmentId, AppointmentStatus status, boolean returned, List<VisitReturns.Task> tasks) {
}
