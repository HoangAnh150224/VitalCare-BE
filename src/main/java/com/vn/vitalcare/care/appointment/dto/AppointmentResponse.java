package com.vn.vitalcare.care.appointment.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.vn.vitalcare.entity.Appointment;
import com.vn.vitalcare.entity.AppointmentStatus;
import com.vn.vitalcare.entity.CustomerStatus;
import com.vn.vitalcare.identity.rowlevel.service.RowLevelSecurity;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * An appointment, with enough of the customer and clinic to show it in a list
 * without a second request per row.
 *
 * <p>The customer's status travels with it so the check-in screen can say,
 * before anyone presses the button, that this arrival will also make them a
 * patient.
 */
public record AppointmentResponse(
        Long id,
        /** The code on the slip and in its QR — the customer's own to show, so both sides see it. */
        String bookingCode,
        CustomerSummary customer,
        ClinicSummary clinic,
        /** Who the visit is with; null on rows from before a doctor was required. */
        DoctorSummary doctor,
        /** The number called at the doctor's door; the times below are about when. */
        Integer queueNumber,
        LocalDate appointmentDate,
        LocalTime startTime,
        LocalTime endTime,
        String reason,
        AppointmentStatus status,
        String note,
        OffsetDateTime checkedInAt,
        /** The examination room the visit is or was in; null until it is called in. */
        RoomSummary examRoom,
        OffsetDateTime calledAt,
        OffsetDateTime cancelledAt,
        /** Why the clinic cancelled it; the customer sees this too. */
        String cancelReason,
        Instant createdAt,
        /**
         * What the caller may do to this row, from the row-level write and
         * delete policies — the framework's {@code _can}, read by the client's
         * access control to hide buttons that would end in a 404 or 422. Empty
         * when there is no scope to report against.
         */
        @JsonProperty("_can")
        Map<String, Boolean> can) {

    public record CustomerSummary(Long id, String customerCode, String fullName, String phone, CustomerStatus status) {
    }

    public record ClinicSummary(UUID id, String name) {
    }

    public record DoctorSummary(Long id, String fullName, String specialty) {
    }

    public record RoomSummary(Long id, String code, String name) {
    }

    /**
     * The same appointment as its customer may see it: without the front
     * desk's internal note, which is written for staff and nobody else.
     */
    public static AppointmentResponse forCustomer(Appointment appointment) {
        AppointmentResponse full = from(appointment);
        return new AppointmentResponse(
                full.id(), full.bookingCode(), full.customer(), full.clinic(), full.doctor(), full.queueNumber(),
                full.appointmentDate(), full.startTime(),
                full.endTime(), full.reason(), full.status(), null, full.checkedInAt(),
                full.examRoom(), full.calledAt(), full.cancelledAt(), full.cancelReason(), full.createdAt(), Map.of());
    }

    /** Without row flags — for a caller with no scope to report against. */
    public static AppointmentResponse from(Appointment appointment) {
        return from(appointment, null);
    }

    public static AppointmentResponse from(Appointment appointment, RowLevelSecurity rowLevel) {
        var customer = appointment.getCustomer();
        var clinic = appointment.getClinic();
        var doctor = appointment.getEmployee();
        var room = appointment.getExamRoom();
        return new AppointmentResponse(
                appointment.getId(),
                appointment.getBookingCode(),
                new CustomerSummary(
                        customer.getId(),
                        customer.getCustomerCode(),
                        customer.getFullName(),
                        customer.getPhone(),
                        customer.getStatus()),
                new ClinicSummary(clinic.getClinicId(), clinic.getClinicName()),
                doctor == null ? null
                        : new DoctorSummary(doctor.getId(), doctor.getUser().getFullName(), doctor.getSpecialty()),
                appointment.getQueueNumber(),
                appointment.getAppointmentDate(),
                appointment.getStartTime(),
                appointment.getEndTime(),
                appointment.getReason(),
                appointment.getStatus(),
                appointment.getNote(),
                appointment.getCheckedInAt(),
                room == null ? null : new RoomSummary(room.getId(), room.getCode(), room.getName()),
                appointment.getCalledAt(),
                appointment.getCancelledAt(),
                appointment.getCancelReason(),
                appointment.getCreatedAt(),
                rowLevel == null ? Map.of() : rowLevel.capabilities(appointment));
    }
}
