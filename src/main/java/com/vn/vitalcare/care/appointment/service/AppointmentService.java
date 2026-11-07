package com.vn.vitalcare.care.appointment.service;

import com.vn.vitalcare.care.appointment.dto.AppointmentUpdateRequest;
import com.vn.vitalcare.care.appointment.dto.BookingRequest;
import com.vn.vitalcare.care.appointment.dto.RescheduleRequest;
import com.vn.vitalcare.care.appointment.dto.StaffBookingRequest;
import com.vn.vitalcare.entity.Appointment;
import com.vn.vitalcare.entity.Customer;
import com.vn.vitalcare.share.web.ListParams;
import java.util.List;
import org.springframework.data.domain.Page;

/**
 * Registered visits: a customer registers with a doctor for a day and gets the
 * day's next queue number; the desk checks them in, which can turn a neutral
 * customer into a patient; the visit is completed, or cancelled, or missed.
 */
public interface AppointmentService {

    /**
     * The appointment a slip's code names, as typed or scanned at the front
     * desk — {@code k7m2-9qxa} finds {@code K7M29QXA}.
     */
    Appointment getByCode(String rawCode);

    Page<Appointment> list(ListParams params);

    /**
     * The appointments of one account — its owner's and their relatives';
     * the filter cannot be widened by anything in {@code params}.
     */
    Page<Appointment> listForCustomer(Customer customer, ListParams params);

    List<Appointment> getMany(List<Long> ids);

    Appointment get(Long id);

    /**
     * One of the account's own appointments: the owner's, or a relative's.
     *
     * <p>Somebody else's id answers exactly as a missing one does — a 404, not
     * a 403 — so the endpoint cannot be used to learn which ids exist.
     */
    Appointment getOwn(Customer customer, Long id);

    /**
     * Out of the room for what the doctor ordered or referred: the room calls
     * its next patient, the visit waits to come back. Only from the room.
     */
    Appointment sendAway(Appointment appointment);

    /**
     * Sends the patient of a visit to another doctor of the same clinic today:
     * a new visit, already checked in and first in that doctor's queue, tied
     * to this one, which leaves the room if it is in one.
     */
    Appointment referFrom(Appointment origin, Long doctorId, String reason);

    /**
     * How many people the doctor will call before this visit: its place in the
     * same order the rooms call in. Null unless it is checked in for today.
     */
    Integer queueAhead(Appointment appointment);

    /**
     * The front desk registering a visit for a customer. Registering never
     * activates anybody.
     *
     * <p>Refused when the doctor does not see patients that day, the day is
     * full or already over, the date is past or beyond the booking window, or
     * the customer is already registered with that doctor that day.
     */
    Appointment bookFor(StaffBookingRequest request);

    /**
     * A customer registering for themselves or for one of the relatives under
     * their account, under the same rules. Refused, on {@code patientId}, when
     * the profile is not theirs or does not yet give a date of birth and gender.
     */
    Appointment bookOwn(Customer customer, BookingRequest request);

    /** Corrects the reason and the desk's note; who, with whom and when change by {@link #reschedule}. */
    Appointment update(Long id, AppointmentUpdateRequest request);

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

    /** The visit happened. Only a checked-in appointment completes. */
    Appointment complete(Long id);

    /**
     * The same, as the consequence of the visit's encounter being completed —
     * not an act of the desk's, so outside the "appointments" scope: the
     * clinician completing an encounter may hold no appointment permission.
     * Does nothing unless the appointment is checked in.
     */
    void completeForEncounter(Long appointmentId);

    /**
     * The visit's examination fee is paid (BF-02): it may now queue, ordered by
     * {@code when}. A visit already checked in joins its doctor's line at once.
     */
    void markExamPaid(Long appointmentId, java.time.OffsetDateTime when);

    /** Somebody walked in: registered for today with the chosen doctor and checked in, in one step. */
    Appointment walkIn(StaffBookingRequest request);

    /** The patient never came. Only from registered, and not before the day itself. */
    Appointment markNoShow(Long id);

    /** The clinic cancelling a customer's appointment; the reason is kept and shown to the customer. */
    Appointment cancel(Long id, String reason);

    Appointment cancelOwn(Customer customer, Long id);

    /**
     * Moves a visit to another day or another doctor, where it gets a new
     * queue number, under the rules a registration and a cancellation already
     * have: still registered, and at least two hours before it was expected
     * (SRS BR-26). The code stays, so the slip the customer holds is still theirs.
     */
    Appointment reschedule(Long id, RescheduleRequest request);

    Appointment rescheduleOwn(Customer customer, Long id, RescheduleRequest request);

    /**
     * UC-02 B1, wrong examinee: the person at the desk is another profile of
     * the same account (the owner or a relative). Only while booked, and the
     * other profile must not already have a visit at that clinic that day.
     */
    Appointment changeExaminee(Long id, Long customerId);

    /** A day a clinic can still take a visit on, and how many places it has left (BR-01). */
    record OpenDay(java.time.LocalDate date, int remaining) {
    }

    /**
     * The days from {@code from} on, within the booking window and at most
     * {@code days} of them, that still have a place: what to offer instead of
     * a full day (UC-01 A1, UC-03 C1). Online and walk-in visits share the places.
     */
    List<OpenDay> openDays(java.util.UUID clinicId, java.time.LocalDate from, int days);

    /**
     * J-01: every visit still booked once its day's last turn is over becomes a
     * no-show, giving its place back (BR-10). Not scoped: the sweep's.
     *
     * @return how many were marked
     */
    int markNoShowsDue();
}
