package com.vn.vitalcare.care.appointment.dto;

import com.vn.vitalcare.entity.Appointment;
import com.vn.vitalcare.entity.AppointmentStatus;
import com.vn.vitalcare.entity.CustomerStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
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
        LocalDate appointmentDate,
        LocalTime startTime,
        LocalTime endTime,
        String reason,
        AppointmentStatus status,
        String note,
        OffsetDateTime checkedInAt,
        OffsetDateTime cancelledAt,
        Instant createdAt) {

    public record CustomerSummary(Long id, String customerCode, String fullName, String phone, CustomerStatus status) {
    }

    public record ClinicSummary(UUID id, String name) {
    }

    /**
     * The same appointment as its customer may see it: without the front
     * desk's internal note, which is written for staff and nobody else.
     */
    public static AppointmentResponse forCustomer(Appointment appointment) {
        AppointmentResponse full = from(appointment);
        return new AppointmentResponse(
                full.id(), full.bookingCode(), full.customer(), full.clinic(), full.appointmentDate(), full.startTime(),
                full.endTime(), full.reason(), full.status(), null, full.checkedInAt(),
                full.cancelledAt(), full.createdAt());
    }

    public static AppointmentResponse from(Appointment appointment) {
        var customer = appointment.getCustomer();
        var user = customer.getUser();
        var clinic = appointment.getClinic();
        return new AppointmentResponse(
                appointment.getId(),
                appointment.getBookingCode(),
                new CustomerSummary(
                        customer.getId(),
                        customer.getCustomerCode(),
                        user.getFullName(),
                        user.getPhone(),
                        customer.getStatus()),
                new ClinicSummary(clinic.getClinicId(), clinic.getClinicName()),
                appointment.getAppointmentDate(),
                appointment.getStartTime(),
                appointment.getEndTime(),
                appointment.getReason(),
                appointment.getStatus(),
                appointment.getNote(),
                appointment.getCheckedInAt(),
                appointment.getCancelledAt(),
                appointment.getCreatedAt());
    }
}
