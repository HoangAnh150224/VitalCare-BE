package com.vn.vitalcare.care.viewas.service;

import com.vn.vitalcare.care.assignment.dto.MyPatientResponse;
import com.vn.vitalcare.entity.Appointment;
import com.vn.vitalcare.share.web.ListParams;
import java.util.List;
import org.springframework.data.domain.Page;

/**
 * A customer's or a member of staff's own screens, as they see them, for an
 * administrator.
 *
 * <p>The same queries as {@code my_appointments} and {@code my_patients}, only
 * with the person named by id instead of taken from the token — so what the
 * administrator sees is exactly what that person sees.
 *
 * <p>Read-only by construction: there is nothing here that books, cancels or
 * changes anything, so nothing can be done in somebody else's name.
 */
public interface ViewAsService {

    /** A customer's "my appointments"; a missing customer answers 404. */
    Page<Appointment> customerAppointments(Long customerId, ListParams params);

    /** One of a customer's appointments; somebody else's appointment answers 404, as it does for them. */
    Appointment customerAppointment(Long customerId, Long appointmentId);

    /** A doctor's or nurse's "my patients"; a missing member of staff answers 404. */
    List<MyPatientResponse> employeePatients(Long employeeId);

    /**
     * Every patient being followed now, with their whole care team — the
     * doctors' and nurses' screens with nobody's caseload left out.
     * {@code employeeId} narrows it to one member of staff's patients.
     */
    List<MyPatientResponse> allPatients(Long employeeId);

    /** One of their patients; a patient they do not follow answers 404, as it does for them. */
    MyPatientResponse employeePatient(Long employeeId, Long customerId);
}
