package com.vn.vitalcare.care.iomt;

import com.vn.vitalcare.care.assignment.repository.DeviceAssignmentRepository;
import com.vn.vitalcare.care.device.repository.MedicalDeviceRepository;
import com.vn.vitalcare.care.notification.Notifier;
import com.vn.vitalcare.entity.Customer;
import com.vn.vitalcare.entity.MedicalDevice;
import com.vn.vitalcare.entity.NotificationLevel;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Marks a device OFFLINE once it has been quiet for {@code app.iomt.offline-after}
 * (FE-02 Device Status Monitoring), and tells whoever wears it to check it —
 * readings stop reaching the clinic until it reports again.
 * ponytail: every node sweeps; the conditional update makes a second sweep a no-op.
 */
@Component
public class DeviceHeartbeat {

    private final MedicalDeviceRepository devices;
    private final DeviceAssignmentRepository assignments;
    private final Notifier notifier;
    private final Clock clock;
    private final Duration offlineAfter;

    public DeviceHeartbeat(MedicalDeviceRepository devices, DeviceAssignmentRepository assignments, Notifier notifier,
                           Clock clock, @Value("${app.iomt.offline-after:5m}") Duration offlineAfter) {
        this.devices = devices;
        this.assignments = assignments;
        this.notifier = notifier;
        this.clock = clock;
        this.offlineAfter = offlineAfter;
    }

    @Scheduled(fixedDelayString = "${app.iomt.heartbeat-sweep:60s}", initialDelayString = "${app.iomt.heartbeat-sweep:60s}")
    @Transactional
    public void sweep() {
        OffsetDateTime cutoff = OffsetDateTime.now(clock).minus(offlineAfter);
        for (MedicalDevice device : devices.findGoneQuiet(cutoff)) {
            if (devices.markOffline(device.getId(), cutoff) == 0) {
                continue;
            }
            assignments.findByDeviceIdInAndUnassignedAtIsNullAndDeletedAtIsNull(java.util.List.of(device.getId()))
                    .stream().findFirst()
                    .ifPresent(open -> notifier.notify(accountOf(open.getCustomer()), NotificationLevel.WARNING,
                            "device", "Thiết bị " + device.getDeviceCode() + " mất kết nối",
                            "Thiết bị theo dõi không gửi dữ liệu từ "
                                    + offlineAfter.toMinutes() + " phút nay. Hãy kiểm tra pin và kết nối Bluetooth/Wi-Fi.",
                            "/my-devices"));
        }
    }

    private static Long accountOf(Customer customer) {
        if (customer.getUser() != null) {
            return customer.getUser().getId();
        }
        Customer guardian = customer.getGuardian();
        return guardian != null && guardian.getUser() != null ? guardian.getUser().getId() : null;
    }
}
