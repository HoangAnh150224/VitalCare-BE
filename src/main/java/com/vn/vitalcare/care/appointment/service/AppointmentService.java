package com.vn.vitalcare.care.appointment.service;

import com.vn.vitalcare.care.appointment.dto.BookingRequest;
import com.vn.vitalcare.care.appointment.dto.StaffBookingRequest;
import com.vn.vitalcare.entity.Appointment;
import com.vn.vitalcare.entity.Customer;
import com.vn.vitalcare.share.web.ListParams;
import java.util.List;
import org.springframework.data.domain.Page;

/**
 * The appointment book: booking, cancelling, and the check-in that can turn a
 * neutral customer into a patient.
 */
public interface AppointmentService {

    /**
     * The appointment a slip's code names, as typed or scanned at the front
     * desk — {@code k7m2-9qxa} finds {@code K7M29QXA}.
     */
    Appointment getByCode(String rawCode);

    Page<Appointment> list(ListParams params);

    /** One customer's appointments; the filter cannot be widened by anything in {@code params}. */
    Page<Appointment> listForCustomer(Customer customer, ListParams params);

    List<Appointment> getMany(List<Long> ids);

    Appointment get(Long id);

    /**
     * One of the customer's own appointments.
     *
     * <p>Somebody else's id answers exactly as a missing one does — a 404, not
     * a 403 — so the endpoint cannot be used to learn which ids exist.
     */
    Appointment getOwn(Customer customer, Long id);

    /** The front desk booking for a customer. Booking never activates anybody. */
    Appointment bookFor(StaffBookingRequest request);

    /** A customer booking for themselves. Booking never activates anybody. */
    Appointment bookOwn(Customer customer, BookingRequest request);

    /**
     * Records the customer's arrival, and makes a neutral customer a patient.
     *
     * <p>Only on the appointment's own date, as the clinic counts days: a
     * check-in recorded on another day is either a mistake or a way to
     * activate somebody without them having turned up, and activation is the
     * one consequence here that matters. A different day needs a manual
     * activation, which says plainly that it was one.
     *
     * <p>The arrival and the activation are one transaction: an arrival
     * recorded without the activation it should have caused would leave the
     * customer neutral with nothing left to trigger it.
     */
    Appointment checkIn(Long id);

    Appointment cancel(Long id);

    Appointment cancelOwn(Customer customer, Long id);
}
