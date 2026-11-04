package com.vn.vitalcare.care.iomt;

import com.vn.vitalcare.care.assignment.repository.DeviceAssignmentRepository;
import com.vn.vitalcare.care.customer.service.CustomerService;
import com.vn.vitalcare.entity.Customer;
import com.vn.vitalcare.entity.DeviceAssignment;
import com.vn.vitalcare.entity.MedicalDevice;
import com.vn.vitalcare.share.exception.ConflictException;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import com.vn.vitalcare.share.security.CurrentUser;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The monitoring devices handed to an account's own profiles — itself and
 * the dependants it manages (FE-02 on the patient side). Every call starts
 * from the profiles behind the access token; a device handed to somebody else
 * is a 404, the same as one that does not exist.
 */
@Service
@Transactional(readOnly = true)
public class MyDevices {

    private final CustomerService customers;
    private final DeviceAssignmentRepository assignments;
    private final com.vn.vitalcare.care.device.repository.MedicalDeviceRepository devices;
    private final VitalIngestion ingestion;
    private final Clock clock;

    public MyDevices(CustomerService customers, DeviceAssignmentRepository assignments,
                     com.vn.vitalcare.care.device.repository.MedicalDeviceRepository devices,
                     VitalIngestion ingestion, Clock clock) {
        this.customers = customers;
        this.assignments = assignments;
        this.devices = devices;
        this.ingestion = ingestion;
        this.clock = clock;
    }

    /** The devices worn now by the caller's profiles, each with who wears it. */
    public List<DeviceAssignment> worn() {
        List<Long> ids = customers.profilesOf(customers.getForCurrentUser()).stream().map(Customer::getId).toList();
        return assignments.findWornByCustomerIdInAndUnassignedAtIsNullAndDeletedAtIsNull(ids);
    }

    /** One of them by its code; 404 for any device not on one of the caller's profiles. */
    public DeviceAssignment wornByCode(String code) {
        String wanted = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        return worn().stream()
                .filter(a -> a.getDevice().getDeviceCode().equalsIgnoreCase(wanted))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Device", wanted));
    }

    /**
     * The phone found the device over BLE and claims it (FE-02 Device Pairing).
     * Only a device handed to one of the caller's profiles; one already paired
     * with somebody else's phone must be unpaired there first.
     */
    @Transactional
    public DeviceAssignment pair(String code) {
        lockByCode(code);
        DeviceAssignment worn = wornByCode(code);
        MedicalDevice device = worn.getDevice();
        Long me = CurrentUser.id().orElseThrow();
        if (device.getPairedByUserId() != null && !device.getPairedByUserId().equals(me)) {
            throw new ConflictException("This device is paired with another phone; unpair it there first");
        }
        device.pair(me, OffsetDateTime.now(clock));
        return worn;
    }

    @Transactional
    public DeviceAssignment unpair(String code) {
        lockByCode(code);
        DeviceAssignment worn = wornByCode(code);
        worn.getDevice().unpair();
        return worn;
    }

    /** Readings the phone collected from the device, offline or live; a write, unlike the class default. */
    @Transactional
    public SyncResult upload(String code, DeviceUpload upload) {
        lockByCode(code);
        DeviceAssignment worn = wornByCode(code);
        // Only into the caller's own time with the device: never a former wearer's record.
        return ingestion.ingest(worn.getDevice().getDeviceCode(), upload, "REST", worn);
    }

    /**
     * Locked before anything is read about it, so the checks that follow see the row as it is
     * held; 404 alike for a code that does not exist and one that is not the caller's.
     */
    private void lockByCode(String code) {
        String wanted = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        devices.findForUpdateByCode(wanted).orElseThrow(() -> new ResourceNotFoundException("Device", wanted));
    }
}
