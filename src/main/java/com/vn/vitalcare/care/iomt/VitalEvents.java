package com.vn.vitalcare.care.iomt;

/** What the intake announces, heard inside its transaction (alert rules, FE-05) or after it (live views). */
public final class VitalEvents {

    private VitalEvents() {
    }

    /** A valid reading was stored for a patient. */
    public record Recorded(Long vitalId, Long customerId, Long deviceId) {
    }
}
