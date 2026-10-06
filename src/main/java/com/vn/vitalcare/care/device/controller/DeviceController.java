package com.vn.vitalcare.care.device.controller;

import com.vn.vitalcare.care.assignment.dto.DeviceAssignmentResponse;
import com.vn.vitalcare.care.device.dto.DevicePatchRequest;
import com.vn.vitalcare.care.device.dto.DeviceRequest;
import com.vn.vitalcare.care.device.dto.DeviceResponse;
import com.vn.vitalcare.care.device.service.DeviceService;
import com.vn.vitalcare.entity.DeviceAssignment;
import com.vn.vitalcare.entity.MedicalDevice;
import com.vn.vitalcare.share.security.Permissions;
import com.vn.vitalcare.share.web.ListParams;
import com.vn.vitalcare.share.web.ListResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/devices} — the clinic's monitoring devices.
 *
 * <p>No delete: a device that is done with is retired, which keeps the record
 * of who wore it — and later, the readings it took.
 */
@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    private final DeviceService service;

    public DeviceController(DeviceService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.DEVICES_READ + "')")
    public ResponseEntity<List<DeviceResponse>> list(@RequestParam MultiValueMap<String, String> query) {
        ListParams params = new ListParams(query);

        List<Long> ids = params.ids();
        if (!ids.isEmpty()) {
            List<DeviceResponse> rows = withWearers(service.getMany(ids));
            return ListResponse.of(rows, rows.size());
        }

        Page<MedicalDevice> page = service.list(params);
        return ListResponse.of(withWearers(page.getContent()), page.getTotalElements());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.DEVICES_READ + "')")
    public DeviceResponse get(@PathVariable Long id) {
        return withWearers(List.of(service.get(id))).getFirst();
    }

    /** Who has worn the device, newest first. */
    @GetMapping("/{id}/assignments")
    @PreAuthorize("hasAuthority('" + Permissions.DEVICES_READ + "')")
    public List<DeviceAssignmentResponse> history(@PathVariable Long id) {
        return service.history(id).stream().map(DeviceAssignmentResponse::ofDevice).toList();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + Permissions.DEVICES_WRITE + "')")
    public ResponseEntity<DeviceResponse> create(@Valid @RequestBody DeviceRequest request) {
        MedicalDevice created = service.register(request);
        return ResponseEntity
                .created(URI.create("/api/devices/" + created.getId()))
                .body(DeviceResponse.from(created, null));
    }

    @RequestMapping(value = "/{id}", method = {RequestMethod.PATCH, RequestMethod.PUT})
    @PreAuthorize("hasAuthority('" + Permissions.DEVICES_WRITE + "')")
    public DeviceResponse update(@PathVariable Long id, @Valid @RequestBody DevicePatchRequest request) {
        return withWearers(List.of(service.update(id, request))).getFirst();
    }

    private List<DeviceResponse> withWearers(List<MedicalDevice> devices) {
        Map<Long, DeviceAssignment> open = service.openAssignments(devices.stream().map(MedicalDevice::getId).toList());
        return devices.stream().map(device -> DeviceResponse.from(device, open.get(device.getId()))).toList();
    }
}
