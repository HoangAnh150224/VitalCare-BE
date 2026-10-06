package com.vn.vitalcare.care.device.service;

import com.vn.vitalcare.care.device.dto.DevicePatchRequest;
import com.vn.vitalcare.care.device.dto.DeviceRequest;
import com.vn.vitalcare.entity.DeviceAssignment;
import com.vn.vitalcare.entity.MedicalDevice;
import com.vn.vitalcare.share.web.ListParams;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;

public interface DeviceService {

    Page<MedicalDevice> list(ListParams params);

    List<MedicalDevice> getMany(List<Long> ids);

    MedicalDevice get(Long id);

    /** Who is wearing each of these devices now, keyed by device id — one query for a whole page. */
    Map<Long, DeviceAssignment> openAssignments(Collection<Long> deviceIds);

    List<DeviceAssignment> history(Long deviceId);

    MedicalDevice register(DeviceRequest request);

    /**
     * Edits a device. A status change takes the device lock and follows the
     * rules the assignment workflow depends on: assigned is set only by
     * handing the device out, and a device on a patient cannot be sent for
     * maintenance or retired until it comes back.
     */
    MedicalDevice update(Long id, DevicePatchRequest request);

    /** The device, locked for the rest of the caller's transaction. */
    MedicalDevice lockForUpdate(Long id);
}
