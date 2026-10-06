package com.vn.vitalcare.care.device.service.impl;

import com.vn.vitalcare.care.assignment.repository.DeviceAssignmentRepository;
import com.vn.vitalcare.care.clinic.service.ClinicService;
import com.vn.vitalcare.care.device.dto.DevicePatchRequest;
import com.vn.vitalcare.care.device.dto.DeviceRequest;
import com.vn.vitalcare.care.device.repository.MedicalDeviceRepository;
import com.vn.vitalcare.care.device.repository.MedicalDeviceSpecifications;
import com.vn.vitalcare.care.device.service.DeviceService;
import com.vn.vitalcare.care.staff.service.ClinicScope;
import com.vn.vitalcare.entity.DeviceAssignment;
import com.vn.vitalcare.entity.DeviceStatus;
import com.vn.vitalcare.entity.MedicalDevice;
import com.vn.vitalcare.share.data.BaseEntitySpecifications;
import com.vn.vitalcare.share.exception.ConflictException;
import com.vn.vitalcare.share.exception.FieldValidationException;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import com.vn.vitalcare.share.web.ListParams;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The {@link DeviceService} the application runs on. */
@Service
@Transactional(readOnly = true)
public class DeviceServiceImpl implements DeviceService {

    private static final Set<String> SORTABLE = Set.of("id", "deviceCode", "status", "registeredAt", "lastSeenAt");

    private static final Sort DEFAULT_SORT = Sort.by(Sort.Order.asc("deviceCode"));

    private final MedicalDeviceRepository repository;
    private final DeviceAssignmentRepository assignments;
    private final ClinicService clinicService;
    private final ClinicScope clinicScope;
    private final Clock clock;

    public DeviceServiceImpl(MedicalDeviceRepository repository,
                             DeviceAssignmentRepository assignments,
                             ClinicService clinicService,
                             ClinicScope clinicScope,
                             Clock clock) {
        this.repository = repository;
        this.assignments = assignments;
        this.clinicService = clinicService;
        this.clinicScope = clinicScope;
        this.clock = clock;
    }

    @Override
    public Page<MedicalDevice> list(ListParams params) {
        return repository.findAll(scope().and(MedicalDeviceSpecifications.from(params)),
                params.pageable(SORTABLE, DEFAULT_SORT));
    }

    @Override
    public List<MedicalDevice> getMany(List<Long> ids) {
        Specification<MedicalDevice> byIds = (root, query, cb) -> root.get("id").in(ids);
        return repository.findAll(Specification.allOf(scope(), BaseEntitySpecifications.notDeleted(), byIds));
    }

    @Override
    public MedicalDevice get(Long id) {
        return repository.findByIdAndDeletedAtIsNull(id)
                .filter(this::inScope)
                .orElseThrow(() -> new ResourceNotFoundException("Device", id));
    }

    @Override
    public Map<Long, DeviceAssignment> openAssignments(Collection<Long> deviceIds) {
        if (deviceIds.isEmpty()) {
            return Map.of();
        }
        return assignments.findByDeviceIdInAndUnassignedAtIsNullAndDeletedAtIsNull(deviceIds).stream()
                .collect(Collectors.toMap(a -> a.getDevice().getId(), Function.identity()));
    }

    @Override
    public List<DeviceAssignment> history(Long deviceId) {
        get(deviceId);
        return assignments.findByDeviceIdAndDeletedAtIsNullOrderByAssignedAtDesc(deviceId);
    }

    @Override
    @Transactional
    public MedicalDevice register(DeviceRequest request) {
        String code = request.deviceCode().trim().toUpperCase(Locale.ROOT);
        if (repository.existsByDeviceCodeIgnoreCase(code)) {
            throw new FieldValidationException("deviceCode", "A device with that code already exists");
        }
        String serial = blankToNull(request.serialNumber());
        if (serial != null && repository.existsBySerialNumberIgnoreCase(serial)) {
            throw new FieldValidationException("serialNumber", "A device with that serial number already exists");
        }

        MedicalDevice device = new MedicalDevice();
        device.setDeviceCode(code);
        device.setSerialNumber(serial);
        device.setDeviceType(blankToNull(request.deviceType()));
        device.setManufacturer(blankToNull(request.manufacturer()));
        device.setModel(blankToNull(request.model()));
        device.setRegisteredAt(OffsetDateTime.now(clock));
        // Registered at the desk's own clinic; by somebody tied to none, at the first.
        clinicScope.currentClinicId()
                .map(clinicService::get)
                .or(() -> clinicService.listAll().stream().findFirst())
                .ifPresent(device::setClinic);
        return repository.save(device);
    }

    @Override
    @Transactional
    public MedicalDevice update(Long id, DevicePatchRequest request) {
        MedicalDevice device = request.status() == null ? get(id) : lockForUpdate(id);

        if (request.serialNumber() != null) {
            String serial = blankToNull(request.serialNumber());
            if (serial != null && !serial.equalsIgnoreCase(device.getSerialNumber())
                    && repository.existsBySerialNumberIgnoreCase(serial)) {
                throw new FieldValidationException("serialNumber", "A device with that serial number already exists");
            }
            device.setSerialNumber(serial);
        }
        if (request.deviceType() != null) {
            device.setDeviceType(blankToNull(request.deviceType()));
        }
        if (request.manufacturer() != null) {
            device.setManufacturer(blankToNull(request.manufacturer()));
        }
        if (request.model() != null) {
            device.setModel(blankToNull(request.model()));
        }
        if (request.status() != null && request.status() != device.getStatus()) {
            if (request.status() == DeviceStatus.ASSIGNED) {
                throw new FieldValidationException("status", "A device becomes assigned by handing it to a patient");
            }
            if (device.getStatus() == DeviceStatus.ASSIGNED
                    || assignments.existsByDeviceIdAndUnassignedAtIsNullAndDeletedAtIsNull(device.getId())) {
                throw new ConflictException("This device is on a patient; take it back first");
            }
            device.setStatus(request.status());
        }
        return repository.save(device);
    }

    @Override
    @Transactional
    public MedicalDevice lockForUpdate(Long id) {
        return repository.findForUpdate(id)
                .filter(this::inScope)
                .orElseThrow(() -> new ResourceNotFoundException("Device", id));
    }

    /** The caller's clinic as a filter; unrestricted for somebody tied to no clinic. */
    private Specification<MedicalDevice> scope() {
        return clinicScope.currentClinicId()
                .map(MedicalDeviceSpecifications::atClinic)
                .orElse((root, query, cb) -> cb.conjunction());
    }

    /** Another clinic's device answers as not found, never as forbidden. */
    private boolean inScope(MedicalDevice device) {
        return clinicScope.currentClinicId()
                .map(clinic -> device.getClinic() != null && clinic.equals(device.getClinic().getClinicId()))
                .orElse(true);
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
