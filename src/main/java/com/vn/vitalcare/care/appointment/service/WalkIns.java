package com.vn.vitalcare.care.appointment.service;

import com.vn.vitalcare.care.appointment.dto.WalkInRequest;
import com.vn.vitalcare.care.clinic.service.ClinicService;
import com.vn.vitalcare.care.registration.service.RegistrationService;
import com.vn.vitalcare.entity.Appointment;
import com.vn.vitalcare.share.exception.FieldValidationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Walk-in registration (FE-10): the desk registers somebody standing at the
 * counter for today and checks them in, in one step — creating their account
 * first when they are new. All or nothing: a refused visit leaves no account
 * behind. The examination fee is taken next; the visit queues once it is paid.
 */
@Service
public class WalkIns {

    private final RegistrationService registration;
    private final AppointmentService appointments;
    private final ClinicService clinics;

    public WalkIns(RegistrationService registration, AppointmentService appointments, ClinicService clinics) {
        this.registration = registration;
        this.appointments = appointments;
        this.clinics = clinics;
    }

    @Transactional
    public Appointment register(WalkInRequest request) {
        Long customerId = request.customerId();
        if (customerId == null) {
            if (request.newPatient() == null) {
                throw new FieldValidationException("customerId", "Pick the patient, or take down a new one");
            }
            WalkInRequest.NewPatient patient = request.newPatient();
            customerId = registration.registerAtDesk(patient.fullName(), patient.phone(), patient.dateOfBirth(),
                    patient.gender()).getId();
        }
        return appointments.walkIn(request.booking(customerId, clinics.today()));
    }
}
