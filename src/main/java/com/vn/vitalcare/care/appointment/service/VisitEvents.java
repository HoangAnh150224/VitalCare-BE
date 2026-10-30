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
}
