package com.vn.vitalcare.care.alert;

import java.util.UUID;

/** What the alert side announces. */
public final class AlertEvents {

    private AlertEvents() {
    }

    /**
     * An alert was raised, or made worse. {@code fresh} is a new alert; otherwise its severity rose.
     * Heard after commit, to notify and to nudge the open screens.
     */
    public record Raised(Long alertId, UUID clinicId, Long customerId, String severity, boolean fresh) {
    }

    /** An alert changed hands or state: the open screens re-read. */
    public record Changed(Long alertId, UUID clinicId) {
    }
}
