package com.vn.vitalcare.care.examroom.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.vn.vitalcare.entity.Appointment;
import com.vn.vitalcare.entity.Employee;
import com.vn.vitalcare.entity.ExamRoomStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One clinic's floor right now: every room, the doctor sitting in it this
 * session, who is inside and who is waiting for that doctor; and the doctors
 * whose patients are waiting with no room to be called into.
 */
public record RoomBoardResponse(
        UUID clinicId,
        OffsetDateTime at,
        List<RoomCard> rooms,
        List<DoctorQueue> withoutRoom) {

    public record RoomCard(
            Long id,
            String code,
            String name,
            ExamRoomStatus status,
            /** The doctor whose session puts them in this room now; null outside any session. */
            DoctorRef doctor,
            Visit current,
            List<Visit> waiting,
            /** This doctor's patients out of the room for ordered services or another doctor. */
            List<AwayVisit> away,
            @JsonProperty("_can")
            Map<String, Boolean> can) {
    }

    public record DoctorQueue(DoctorRef doctor, List<Visit> waiting, List<AwayVisit> away) {
    }

    /** Out of the room: who, and how much of what they went out for is done. */
    public record AwayVisit(Visit visit, int done, int total, List<String> pending) {
    }

    public record DoctorRef(Long id, String fullName, String specialty) {

        public static DoctorRef of(Employee doctor) {
            return new DoctorRef(doctor.getId(), doctor.getUser().getFullName(), doctor.getSpecialty());
        }
    }

    public record Visit(
            Long appointmentId,
            Integer queueNumber,
            Long customerId,
            String patientName,
            String customerCode,
            OffsetDateTime checkedInAt,
            OffsetDateTime calledAt,
            /** Called before and not there: waits behind everybody not yet called. */
            boolean passedOver,
            /** Back from ordered services or sent by a colleague: called first. */
            boolean returned,
            /** The doctor who referred the patient here today, if any. */
            String referredBy) {

        public static Visit of(Appointment appointment) {
            var customer = appointment.getCustomer();
            return new Visit(
                    appointment.getId(),
                    appointment.getQueueNumber(),
                    customer.getId(),
                    customer.getFullName(),
                    customer.getCustomerCode(),
                    appointment.getCheckedInAt(),
                    appointment.getCalledAt(),
                    appointment.getSkippedAt() != null,
                    appointment.getReturnedAt() != null,
                    appointment.getReferredFrom() == null || appointment.getReferredFrom().getEmployee() == null
                            ? null : appointment.getReferredFrom().getEmployee().getUser().getFullName());
        }
    }
}
