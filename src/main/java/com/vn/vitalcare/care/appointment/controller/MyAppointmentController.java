package com.vn.vitalcare.care.appointment.controller;

import com.vn.vitalcare.care.appointment.dto.AppointmentResponse;
import com.vn.vitalcare.care.appointment.dto.BookingRequest;
import com.vn.vitalcare.care.appointment.dto.RescheduleRequest;
import com.vn.vitalcare.care.appointment.service.AppointmentService;
import com.vn.vitalcare.care.appointment.service.VisitReturns;
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
    private final VisitReturns visitReturns;

    public MyAppointmentController(AppointmentService service, CustomerService customerService,
                                   VisitReturns visitReturns) {
        this.service = service;
        this.customerService = customerService;
        this.visitReturns = visitReturns;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.MY_APPOINTMENTS_READ + "')")
    public ResponseEntity<List<AppointmentResponse>> list(@RequestParam MultiValueMap<String, String> query) {
        Customer customer = customerService.getForCurrentUser();
        Page<Appointment> page = service.listForCustomer(customer, new ListParams(query));
        return ListResponse.of(page.map(AppointmentResponse::forCustomer).getContent(), page.getTotalElements());
    }

    /**
     * How the account may pay its next examination fees (UC-01 step 4, BR-11):
     * after {@link AppointmentService#NO_SHOWS_FOR_ONLINE_ONLY} no-shows only
     * online, so the portal does not offer paying at the desk.
     */
    @GetMapping("/payment-rules")
    @PreAuthorize("hasAuthority('" + Permissions.MY_APPOINTMENTS_READ + "')")
    public Map<String, Object> paymentRules() {
        long noShows = service.noShowsOfAccount(customerService.getForCurrentUser());
        return Map.of("noShows", noShows,
                "onlinePaymentRequired", noShows >= AppointmentService.NO_SHOWS_FOR_ONLINE_ONLY);
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
        // BF-02: checked in but the examination fee is not paid, so not in the line yet.
        body.put("awaitingPayment", appointment.getStatus() == com.vn.vitalcare.entity.AppointmentStatus.CHECKED_IN);
        // Called while away: it waits again, behind those never called, until the recall time.
        body.put("skipped", appointment.getSkippedAt() != null);
        // Back from what the doctor sent them to do, or sent by a colleague: called first.
        body.put("returned", appointment.getReturnedAt() != null);
        // Out of the room: what for, and how far along. Empty for a visit that never left.
        body.put("tasks", visitReturns.tasksOf(appointment));
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

    /** UC-01 step 4: pays the examination fee at the desk on the day; the patient is told so by SMS. */
    @org.springframework.web.bind.annotation.PutMapping("/{id}/desk-payment")
    @PreAuthorize("hasAuthority('" + Permissions.MY_APPOINTMENTS_WRITE + "')")
    public AppointmentResponse payAtDesk(@PathVariable Long id) {
        return AppointmentResponse.forCustomer(
                service.choosePaymentAtDesk(customerService.getForCurrentUser(), id, true));
    }

    /** Pays online after all. */
    @org.springframework.web.bind.annotation.DeleteMapping("/{id}/desk-payment")
    @PreAuthorize("hasAuthority('" + Permissions.MY_APPOINTMENTS_WRITE + "')")
    public AppointmentResponse payOnline(@PathVariable Long id) {
        return AppointmentResponse.forCustomer(
                service.choosePaymentAtDesk(customerService.getForCurrentUser(), id, false));
    }

    @PostMapping("/{id}/reschedule")
    @PreAuthorize("hasAuthority('" + Permissions.MY_APPOINTMENTS_WRITE + "')")
    public AppointmentResponse reschedule(@PathVariable Long id, @Valid @RequestBody RescheduleRequest request) {
        return AppointmentResponse.forCustomer(
                service.rescheduleOwn(customerService.getForCurrentUser(), id, request));
    }
}
