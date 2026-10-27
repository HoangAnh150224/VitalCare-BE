package com.vn.vitalcare.care.appointment.controller;

import com.vn.vitalcare.care.appointment.dto.AppointmentResponse;
import com.vn.vitalcare.care.appointment.dto.BookingRequest;
import com.vn.vitalcare.care.appointment.dto.RescheduleRequest;
import com.vn.vitalcare.care.appointment.service.AppointmentService;
import com.vn.vitalcare.care.customer.service.CustomerService;
import com.vn.vitalcare.entity.Appointment;
import com.vn.vitalcare.entity.Customer;
import com.vn.vitalcare.share.security.Permissions;
import com.vn.vitalcare.share.web.ListParams;
import com.vn.vitalcare.share.web.ListResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/my_appointments} — a customer's own appointments.
 *
 * <p>Every call starts from the customer record behind the access token and
 * never from anything in the request, so there is no id a client could send
 * to reach somebody else's booking.
 */
@RestController
@RequestMapping("/api/my_appointments")
public class MyAppointmentController {

    private final AppointmentService service;
    private final CustomerService customerService;

    public MyAppointmentController(AppointmentService service, CustomerService customerService) {
        this.service = service;
        this.customerService = customerService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.MY_APPOINTMENTS_READ + "')")
    public ResponseEntity<List<AppointmentResponse>> list(@RequestParam MultiValueMap<String, String> query) {
        Customer customer = customerService.getForCurrentUser();
        Page<Appointment> page = service.listForCustomer(customer, new ListParams(query));
        return ListResponse.of(page.map(AppointmentResponse::forCustomer).getContent(), page.getTotalElements());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.MY_APPOINTMENTS_READ + "')")
    public AppointmentResponse get(@PathVariable Long id) {
        return AppointmentResponse.forCustomer(service.getOwn(customerService.getForCurrentUser(), id));
    }

    /**
     * Where the visit stands in the doctor's queue: {@code ahead} people before
     * it, or null when it is not waiting today, and whether it was passed over. The portal re-reads this when
     * {@code /user/queue/my_appointments} says the clinic's queue moved.
     */
    @GetMapping("/{id}/queue")
    @PreAuthorize("hasAuthority('" + Permissions.MY_APPOINTMENTS_READ + "')")
    public Map<String, Object> queue(@PathVariable Long id) {
        Appointment appointment = service.getOwn(customerService.getForCurrentUser(), id);
        // A HashMap, not Map.of: ahead is null when the visit is not waiting.
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("ahead", service.queueAhead(appointment));
        // Called while away: it waits again, behind those never called, until the recall time.
        body.put("skipped", appointment.getSkippedAt() != null);
        return body;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + Permissions.MY_APPOINTMENTS_WRITE + "')")
    public ResponseEntity<AppointmentResponse> create(@Valid @RequestBody BookingRequest request) {
        Appointment created = service.bookOwn(customerService.getForCurrentUser(), request);
        return ResponseEntity
                .created(URI.create("/api/my_appointments/" + created.getId()))
                .body(AppointmentResponse.forCustomer(created));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('" + Permissions.MY_APPOINTMENTS_WRITE + "')")
    public AppointmentResponse cancel(@PathVariable Long id) {
        return AppointmentResponse.forCustomer(service.cancelOwn(customerService.getForCurrentUser(), id));
    }

    @PostMapping("/{id}/reschedule")
    @PreAuthorize("hasAuthority('" + Permissions.MY_APPOINTMENTS_WRITE + "')")
    public AppointmentResponse reschedule(@PathVariable Long id, @Valid @RequestBody RescheduleRequest request) {
        return AppointmentResponse.forCustomer(
                service.rescheduleOwn(customerService.getForCurrentUser(), id, request));
    }
}
