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
import com.vn.vitalcare.identity.rowlevel.service.RowLevelSecurity;
import com.vn.vitalcare.share.data.BaseEntitySpecifications;
import com.vn.vitalcare.share.exception.ConflictException;
import com.vn.vitalcare.share.exception.FieldValidationException;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import com.vn.vitalcare.share.security.rowlevel.Action;
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
    // Only to pick the clinic a new device is registered at; which clinics'
    // devices the caller reaches is the "devices" row-level policy's.
    private final ClinicScope clinicScope;
    private final RowLevelSecurity rowLevel;
    private final Clock clock;

    public DeviceServiceImpl(MedicalDeviceRepository repository,
                             DeviceAssignmentRepository assignments,
                             ClinicService clinicService,
                             ClinicScope clinicScope,
                             RowLevelSecurity rowLevel,
                             Clock clock) {
        this.repository = repository;
        this.assignments = assignments;
        this.clinicService = clinicService;
        this.clinicScope = clinicScope;
        this.rowLevel = rowLevel;
        this.clock = clock;
    }

    @Override
    public Page<MedicalDevice> list(ListParams params) {
        return repository.findAll(
                rowLevel.scope(MedicalDevice.class, Action.READ).and(MedicalDeviceSpecifications.from(params)),
                params.pageable(SORTABLE, DEFAULT_SORT));
    }

    @Override
    public List<MedicalDevice> getMany(List<Long> ids) {
        return repository.findAll(Specification.allOf(
                rowLevel.byIds(MedicalDevice.class, Action.READ, ids), BaseEntitySpecifications.notDeleted()));
    }

    @Override
    public MedicalDevice get(Long id) {
        return find(id, Action.READ);
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
        MedicalDevice saved = repository.persist(device);
        // WITH CHECK against the complete row, after the flush; a rollback
        // undoes the insert if it is refused.
        repository.flush();
        rowLevel.checkWritable(saved);
        return saved;
    }

    @Override
    @Transactional
    public MedicalDevice update(Long id, DevicePatchRequest request) {
        // USING through the write scope: a device the caller may see but not
        // change is a 404.
        MedicalDevice device = request.status() == null ? find(id, Action.WRITE) : lockForUpdate(id);

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
        rowLevel.checkWritable(device);
        return repository.persist(device);
    }

    @Override
    @Transactional
    public MedicalDevice lockForUpdate(Long id) {
        // Write scope: every caller locks a device to change it -- its own
        // update, or handing it to a patient and taking it back. The caller
        // checks WITH CHECK on the state it leaves the device in.
        return lock(id, Action.WRITE);
    }

    /**
     * One row through the caller's scope for {@code action}, in the query
     * itself. Outside it is a 404, indistinguishable from a row that does not
     * exist -- deliberately, as in the framework's {@code TaskService}.
     */
    private MedicalDevice find(Long id, Action action) {
        return repository.findOne(Specification.allOf(
                        rowLevel.byId(MedicalDevice.class, action, id), BaseEntitySpecifications.notDeleted()))
                .orElseThrow(() -> new ResourceNotFoundException("Device", id));
    }

    /**
     * The row under a pessimistic lock, then the same scope check as
     * {@link #find}. The lock query takes no specification, so the scope is
     * asked of the database separately, after the lock is held -- the row it
     * answers about is the one this transaction now owns.
     */
    private MedicalDevice lock(Long id, Action action) {
        MedicalDevice row = repository.findForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Device", id));
        if (!repository.exists(rowLevel.byId(MedicalDevice.class, action, id))) {
            throw new ResourceNotFoundException("Device", id);
        }
        return row;
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
