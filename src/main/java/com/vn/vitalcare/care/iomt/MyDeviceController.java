package com.vn.vitalcare.care.iomt;

import com.vn.vitalcare.entity.DeviceAssignment;
import com.vn.vitalcare.entity.MedicalDevice;
import com.vn.vitalcare.share.security.Permissions;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/my_devices} — the patient app's side of a monitoring device (FE-02, FE-03). */
@RestController
@RequestMapping("/api/my_devices")
public class MyDeviceController {

    private final MyDevices myDevices;

    public MyDeviceController(MyDevices myDevices) {
        this.myDevices = myDevices;
    }

    public record MyDevice(Long deviceId, String deviceCode, String deviceType, String model, Long customerId,
                           String customerName, OffsetDateTime assignedAt, OffsetDateTime expectedReturnAt,
                           String connectivity, Integer batteryLevel, String firmwareVersion,
                           OffsetDateTime lastSeenAt, OffsetDateTime lastSyncAt, OffsetDateTime pairedAt,
                           boolean pairedWithMe) {

        static MyDevice of(DeviceAssignment worn, Long me) {
            MedicalDevice d = worn.getDevice();
            return new MyDevice(d.getId(), d.getDeviceCode(), d.getDeviceType(), d.getModel(),
                    worn.getCustomer().getId(), worn.getCustomer().getFullName(), worn.getAssignedAt(),
                    worn.getExpectedReturnAt(), d.getConnectivity(), d.getBatteryLevel(), d.getFirmwareVersion(),
                    d.getLastSeenAt(), d.getLastSyncAt(), d.getPairedAt(),
                    me != null && me.equals(d.getPairedByUserId()));
        }
    }

    public record PairRequest(String deviceCode) {
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.MY_DEVICES_READ + "')")
    public List<MyDevice> list() {
        Long me = com.vn.vitalcare.share.security.CurrentUser.id().orElse(null);
        return myDevices.worn().stream().map(w -> MyDevice.of(w, me)).toList();
    }

    @GetMapping("/{code}")
    @PreAuthorize("hasAuthority('" + Permissions.MY_DEVICES_READ + "')")
    public MyDevice get(@PathVariable String code) {
        return MyDevice.of(myDevices.wornByCode(code), com.vn.vitalcare.share.security.CurrentUser.id().orElse(null));
    }

    @PostMapping("/pair")
    @PreAuthorize("hasAuthority('" + Permissions.MY_DEVICES_WRITE + "')")
    public MyDevice pair(@RequestBody PairRequest request) {
        return MyDevice.of(myDevices.pair(request.deviceCode()),
                com.vn.vitalcare.share.security.CurrentUser.id().orElse(null));
    }

    @DeleteMapping("/{code}/pair")
    @PreAuthorize("hasAuthority('" + Permissions.MY_DEVICES_WRITE + "')")
    public MyDevice unpair(@PathVariable String code) {
        return MyDevice.of(myDevices.unpair(code), com.vn.vitalcare.share.security.CurrentUser.id().orElse(null));
    }

    /** Readings collected by the phone from the device; see docs/mobile-api.md. */
    @PostMapping("/{code}/readings")
    @PreAuthorize("hasAuthority('" + Permissions.MY_DEVICES_WRITE + "')")
    public SyncResult upload(@PathVariable String code, @Valid @RequestBody DeviceUpload upload) {
        return myDevices.upload(code, upload);
    }
}
