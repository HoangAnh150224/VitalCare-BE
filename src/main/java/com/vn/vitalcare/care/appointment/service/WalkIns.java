package com.vn.vitalcare.care.appointment.service;

import com.vn.vitalcare.care.appointment.dto.WalkInRequest;
import com.vn.vitalcare.care.clinic.service.ClinicService;
import com.vn.vitalcare.care.customer.service.CustomerService;
import com.vn.vitalcare.entity.Appointment;
import com.vn.vitalcare.share.exception.FieldValidationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Walk-in registration (FE-10): the desk registers somebody standing at the
 * counter for today and checks them in, in one step. A new walk-in gets a
 * patient profile without an account. All or nothing: a refused visit leaves
 * no profile behind. The examination fee is taken next; the visit queues once
 * it is paid.
 */
@Service
public class WalkIns {

    private final CustomerService customers;
    private final AppointmentService appointments;
    private final ClinicService clinics;

    public WalkIns(CustomerService customers, AppointmentService appointments, ClinicService clinics) {
        this.customers = customers;
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
            customerId = customers.createWalkInProfile(patient.fullName(), patient.phone(), patient.dateOfBirth(),
                    patient.gender(), patient.nationalId()).getId();
        }
        return appointments.walkIn(request.booking(customerId, clinics.today()));
    }
}
