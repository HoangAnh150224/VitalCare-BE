package com.vn.vitalcare.care.appointment.service;

import com.vn.vitalcare.care.appointment.dto.StaffBookingRequest;
import com.vn.vitalcare.care.appointment.dto.WalkInRequest;
import com.vn.vitalcare.care.billing.invoice.Invoices;
import com.vn.vitalcare.care.clinic.service.ClinicService;
import com.vn.vitalcare.care.customer.service.CustomerService;
import com.vn.vitalcare.entity.Appointment;
import com.vn.vitalcare.entity.AppointmentStatus;
import com.vn.vitalcare.share.exception.ConflictException;
import com.vn.vitalcare.share.exception.FieldValidationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Walk-in registration (FE-10, UC-03): the desk registers somebody standing at
 * the counter for today and checks them in, in one step. A new walk-in gets a
 * patient profile without an account. All or nothing: a refused visit leaves
 * no profile behind. The examination fee is taken next; the visit queues once
 * it is paid.
 */
@Service
public class WalkIns {

    private final CustomerService customers;
    private final AppointmentService appointments;
    private final ClinicService clinics;
    private final Invoices invoices;

    public WalkIns(CustomerService customers, AppointmentService appointments, ClinicService clinics,
                   Invoices invoices) {
        this.customers = customers;
        this.appointments = appointments;
        this.clinics = clinics;
        this.invoices = invoices;
    }

    @Transactional
    public Appointment register(WalkInRequest request) {
        Long customerId = request.customerId();
        if (customerId == null) {
            if (request.newPatient() == null) {
                throw new FieldValidationException("customerId", "Pick the patient, or take down a new one");
            }
            WalkInRequest.NewPatient patient = request.newPatient();
            customerId = customers.createWalkInProfile(patient.fullName(), patient.phone(), patient.dateOfBirth(),
                    patient.gender(), patient.nationalId()).getId();
        }
        return appointments.walkIn(request.booking(customerId, clinics.today()));
    }

    /**
     * UC-02 B1, "convert to walk-in": the patient came for a visit that can no
     * longer be checked in — its day has passed, it was missed, or it was
     * cancelled. They are registered and checked in for today instead, under
     * the same profile; an examination fee already paid online pays for the
     * new visit. A visit still booked for a past day becomes a no-show. All or
     * nothing, like {@link #register}.
     */
    @Transactional
    public Appointment replace(Long appointmentId) {
        Appointment old = appointments.get(appointmentId);
        boolean pastDay = old.getAppointmentDate().isBefore(clinics.today());
        boolean over = old.getStatus() == AppointmentStatus.NO_SHOW
                || old.getStatus() == AppointmentStatus.CANCELLED
                || (old.getStatus() == AppointmentStatus.BOOKED && pastDay);
        if (!over) {
            throw new ConflictException("This appointment can be checked in as it is, or is already under way");
        }
        // Converted once: a second press (or a second desk) finds today's visit and stops.
        if (appointments.hasOpenVisitAt(old.getCustomer(), old.getClinicId(), clinics.today())) {
            throw new ConflictException("There is already a visit registered at that clinic on that day");
        }
        Appointment fresh = appointments.walkIn(new StaffBookingRequest(old.getCustomer().getId(), null,
                old.getClinicId(), clinics.today(), old.getReason() == null ? "Khám" : old.getReason(),
                "Thay cho lịch " + old.getBookingCode()));
        // A fee paid on a visit that was late or missed pays for this one (UC-02 B1). A cancelled
        // visit's paid fee is not carried: its cancellation already flagged it for a refund.
        if (old.getStatus() != AppointmentStatus.CANCELLED) {
            // Before the old visit is closed, so closing it finds no paid bill to flag.
            invoices.carryExamPaid(old.getId(), fresh);
        }
        if (old.getStatus() == AppointmentStatus.BOOKED) {
            appointments.markNoShow(old.getId());
        }
        return fresh;
    }
}
