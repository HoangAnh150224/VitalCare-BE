package com.vn.vitalcare.care.viewas;

import com.vn.vitalcare.care.appointment.dto.AppointmentResponse;
import com.vn.vitalcare.care.assignment.dto.MyPatientResponse;
import com.vn.vitalcare.care.viewas.service.ViewAsService;
import com.vn.vitalcare.entity.Appointment;
import com.vn.vitalcare.share.security.Permissions;
import com.vn.vitalcare.share.web.ListParams;
import com.vn.vitalcare.share.web.ListResponse;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/view_as} — a customer's or a member of staff's own screens, as
 * they see them, for an administrator.
 *
 * <p>The same response shapes as {@code my_appointments} and
 * {@code my_patients}: appointments go through
 * {@link AppointmentResponse#forCustomer}, so the internal note is absent here
 * exactly as it is for the customer.
 *
 * <p>GET only, on purpose. Nobody signs in as anybody: the administrator stays
 * themselves, and there is no endpoint here through which anything could be
 * booked, cancelled or changed in somebody else's name.
 */
@RestController
@RequestMapping("/api/view_as")
public class ViewAsController {

    private final ViewAsService service;

    public ViewAsController(ViewAsService service) {
        this.service = service;
    }

    /** A customer's "my appointments". */
    @GetMapping("/customers/{customerId}/appointments")
    @PreAuthorize("hasAuthority('" + Permissions.VIEW_AS_READ + "')")
    public ResponseEntity<List<AppointmentResponse>> customerAppointments(
            @PathVariable Long customerId, @RequestParam MultiValueMap<String, String> query) {
        Page<Appointment> page = service.customerAppointments(customerId, new ListParams(query));
        return ListResponse.of(page.map(AppointmentResponse::forCustomer).getContent(), page.getTotalElements());
    }

    /** One of a customer's appointment slips. */
    @GetMapping("/customers/{customerId}/appointments/{appointmentId}")
    @PreAuthorize("hasAuthority('" + Permissions.VIEW_AS_READ + "')")
    public AppointmentResponse customerAppointment(@PathVariable Long customerId, @PathVariable Long appointmentId) {
        return AppointmentResponse.forCustomer(service.customerAppointment(customerId, appointmentId));
    }

    /** A doctor's or nurse's "my patients". */
    @GetMapping("/employees/{employeeId}/patients")
    @PreAuthorize("hasAuthority('" + Permissions.VIEW_AS_READ + "')")
    public ResponseEntity<List<MyPatientResponse>> employeePatients(@PathVariable Long employeeId) {
        List<MyPatientResponse> rows = service.employeePatients(employeeId);
        return ListResponse.of(rows, rows.size());
    }

    /** Every patient being followed now, with their whole care team; {@code employeeId} narrows it. */
    @GetMapping("/patients")
    @PreAuthorize("hasAuthority('" + Permissions.VIEW_AS_READ + "')")
    public ResponseEntity<List<MyPatientResponse>> allPatients(@RequestParam(required = false) Long employeeId) {
        List<MyPatientResponse> rows = service.allPatients(employeeId);
        return ListResponse.of(rows, rows.size());
    }

    /** One of their patients. */
    @GetMapping("/employees/{employeeId}/patients/{customerId}")
    @PreAuthorize("hasAuthority('" + Permissions.VIEW_AS_READ + "')")
    public MyPatientResponse employeePatient(@PathVariable Long employeeId, @PathVariable Long customerId) {
        return service.employeePatient(employeeId, customerId);
    }
}
