package com.vn.vitalcare.care.iomt;

import java.util.List;
import java.util.Objects;

/** What the intake announces, heard inside its transaction (alert rules, FE-05) or after it (live views). */
public final class VitalEvents {

    private VitalEvents() {
    }

    /**
     * A valid reading was stored for a patient. {@code deviceAlerts} is what the
     * device itself flagged in it, never null.
     */
    public record Recorded(Long vitalId, Long customerId, Long deviceId, List<DeviceUpload.DeviceAlert> deviceAlerts) {

        public Recorded {
            // From the wire: a null list, null entries and a flood are all possible.
            deviceAlerts = deviceAlerts == null ? List.of()
                    : deviceAlerts.stream().filter(Objects::nonNull).limit(8).toList();
        }

        public Recorded(Long vitalId, Long customerId, Long deviceId) {
            this(vitalId, customerId, deviceId, List.of());
        }
    }
}
