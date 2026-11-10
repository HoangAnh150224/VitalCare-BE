package com.vn.vitalcare.care.appointment.service;

/**
 * What moves a visit that is away from the room back into its doctor's
 * queue. Published inside the transaction that made the change, and heard
 * there ({@link VisitReturns}), so the return commits with it.
 */
public final class VisitEvents {

    private VisitEvents() {
    }

    /** A service ordered at a visit got its result, or was cancelled. */
    public record OrdersChanged(Long appointmentId) {
    }

    /** A visit is over (completed, or the patient never came): a referral's origin may be free to return. */
    public record VisitEnded(Long appointmentId) {
    }

    /** A visit was registered, by its patient or at the desk: its examination fee is billed (BF-02). */
    public record Registered(Long appointmentId) {
    }

    /** The patient chose to pay a visit's examination fee at the desk: they are told so by SMS. */
    public record DeskPaymentChosen(Long appointmentId) {
    }

    /** A visit's examination fee was paid: it may queue. */
    public record ExamPaid(Long appointmentId) {
    }

    /** A visit is about to be cancelled or marked a no-show: heard first, so its bill is locked before it. */
    public record Cancelling(Long appointmentId) {
    }

    /** A visit moved to another doctor or day: its unpaid bill follows it. */
    public record Rescheduled(Long appointmentId) {
    }

    /** A registered visit was cancelled or the patient never came: an unpaid examination bill is called off. */
    public record Cancelled(Long appointmentId) {
    }
}
