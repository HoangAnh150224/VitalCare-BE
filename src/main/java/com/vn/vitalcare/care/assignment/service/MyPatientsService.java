package com.vn.vitalcare.care.assignment.service;

import com.vn.vitalcare.care.assignment.dto.MyPatientResponse;
import com.vn.vitalcare.entity.Employee;
import java.util.List;

/**
 * "My patients": the patients the signed-in member of staff is following now.
 *
 * <p>Every answer starts from the caller's own staff record and their open
 * assignments, never from anything in the request — as {@code my_appointments}
 * does for customers. A patient they do not follow answers as not found.
 */
public interface MyPatientsService {

    List<MyPatientResponse> list();

    MyPatientResponse get(Long customerId);

    /**
     * A given member of staff's caseload, exactly as they see it. Used by the
     * signed-in member of staff and by an administrator viewing as them.
     */
    List<MyPatientResponse> list(Employee me);

    /**
     * Every patient being followed now, each with their whole care team and
     * device — every caseload at once. With {@code employeeId}, only the
     * patients that member of staff follows, still with the whole team.
     */
    List<MyPatientResponse> listAll(Long employeeId);

    /** One patient of a given member of staff; a patient they do not follow answers as not found. */
    MyPatientResponse get(Employee me, Long customerId);
}
