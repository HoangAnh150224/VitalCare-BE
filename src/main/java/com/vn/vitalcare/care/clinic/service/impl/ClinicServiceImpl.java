package com.vn.vitalcare.care.clinic.service.impl;

import com.vn.vitalcare.care.clinic.dto.ClinicSummaryResponse;
import com.vn.vitalcare.care.clinic.ClinicProperties;
import com.vn.vitalcare.care.clinic.dto.ClinicRequest;
import com.vn.vitalcare.care.clinic.repository.ClinicRepository;
import com.vn.vitalcare.care.clinic.service.ClinicService;
import com.vn.vitalcare.entity.Clinic;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import com.vn.vitalcare.share.exception.FieldValidationException;
import com.vn.vitalcare.share.audit.AuditEvent;
import com.vn.vitalcare.share.security.CurrentUser;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The {@link ClinicService} the application runs on. */
@Service
@Transactional(readOnly = true)
public class ClinicServiceImpl implements ClinicService {

    private final ClinicRepository repository;
    private final ClinicProperties properties;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    public ClinicServiceImpl(ClinicRepository repository, ClinicProperties properties, Clock clock,
                             ApplicationEventPublisher events) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
        this.events = events;
    }

    @Override
    public List<Clinic> listAll() {
        return repository.findByDeletedAtIsNullOrderByClinicNameAsc();
    }

    @Override
    public Clinic get(UUID id) {
        return repository.findByClinicIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Clinic", id));
    }

    @Override
    @Transactional
    public Clinic create(ClinicRequest request) {
        ensureNameAvailable(request.name(), null);
        Clinic clinic = new Clinic();
        clinic.setClinicId(UUID.randomUUID());
        clinic.setOperatingStatus("OPERATING");
        Clinic saved = repository.save(apply(clinic, request));
        events.publishEvent(new AuditEvent("clinics", null, "create", null, state(saved)));
        return saved;
    }

    @Override
    @Transactional
    public Clinic update(UUID id, ClinicRequest request) {
        Clinic clinic = get(id);
        ensureNameAvailable(request.name(), id);
        Map<String, Object> before = state(clinic);
        Clinic saved = repository.save(apply(clinic, request));
        Map<String, Object> after = state(saved);
        String action = statusOnlyChange(before, after) ? "status_change" : "update";
        events.publishEvent(new AuditEvent("clinics", null, action, before, after));
        return saved;
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        Clinic clinic = get(id);
        Map<String, Object> before = state(clinic);
        clinic.markDeleted(CurrentUser.id().orElse(null), clock.instant());
        repository.save(clinic);
        events.publishEvent(new AuditEvent("clinics", null, "delete", before, null));
    }

    @Override
    public long staffCount(UUID id) {
        return repository.countActiveStaff(id);
    }

    @Override
    public ClinicSummaryResponse summary(UUID id) {
        get(id);
        return new ClinicSummaryResponse(
                repository.countActiveStaff(id), repository.countDevices(id), repository.countPatients(id));
    }

    private static Clinic apply(Clinic clinic, ClinicRequest request) {
        clinic.setClinicName(request.name().trim());
        clinic.setAddress(request.address() == null || request.address().isBlank() ? null : request.address().trim());
        clinic.setContactPhone(
                request.contactPhone() == null || request.contactPhone().isBlank() ? null : request.contactPhone().trim());
        clinic.setContactEmail(request.contactEmail().trim());
        clinic.setContactPerson(
                request.contactPerson() == null || request.contactPerson().isBlank() ? null : request.contactPerson().trim());
        if (request.operatingStatus() != null && !request.operatingStatus().isBlank()) {
            clinic.setOperatingStatus(request.operatingStatus());
        }
        return clinic;
    }

    private void ensureNameAvailable(String name, UUID clinicId) {
        String normalized = name.trim();
        boolean taken = clinicId == null
                ? repository.existsByClinicNameIgnoreCaseAndDeletedAtIsNull(normalized)
                : repository.existsByClinicNameIgnoreCaseAndDeletedAtIsNullAndClinicIdNot(normalized, clinicId);
        if (taken) {
            throw new FieldValidationException("name", "Tên cơ sở đã tồn tại. Hãy đặt tên khác.");
        }
    }

    @Override
    @Transactional
    public Clinic changeOperatingStatus(UUID id, String status) {
        if (!"OPERATING".equals(status) && !"INACTIVE".equals(status)) {
            throw new IllegalArgumentException("Invalid clinic status");
        }
        Clinic clinic = getForUpdate(id);
        Map<String, Object> before = state(clinic);
        clinic.setOperatingStatus(status);
        Clinic saved = repository.save(clinic);
        events.publishEvent(new AuditEvent("clinics", null, "status_change", before, state(saved)));
        return saved;
    }

    @Override
    @Transactional
    public Clinic getForUpdate(UUID id) {
        return repository.findForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Clinic", id));
    }

    @Override
    public ZoneId timeZone() {
        return properties.timeZone();
    }

    @Override
    public LocalDate today() {
        return LocalDate.now(clock.withZone(properties.timeZone()));
    }

    @Override
    public LocalDate lastBookableDate() {
        return today().plusDays(properties.bookingWindowDays());
    }

    @Override
    public boolean isWithinBookingWindow(LocalDate date) {
        return !date.isBefore(today()) && !date.isAfter(lastBookableDate());
    }

    private static Map<String, Object> state(Clinic clinic) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("clinicId", clinic.getClinicId().toString());
        state.put("name", clinic.getClinicName());
        state.put("address", clinic.getAddress());
        state.put("contactPhone", clinic.getContactPhone());
        state.put("contactEmail", clinic.getContactEmail());
        state.put("contactPerson", clinic.getContactPerson());
        state.put("operatingStatus", clinic.getOperatingStatus());
        return state;
    }

    private static boolean statusOnlyChange(Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> beforeOther = new LinkedHashMap<>(before);
        Map<String, Object> afterOther = new LinkedHashMap<>(after);
        Object beforeStatus = beforeOther.remove("operatingStatus");
        Object afterStatus = afterOther.remove("operatingStatus");
        return !java.util.Objects.equals(beforeStatus, afterStatus) && beforeOther.equals(afterOther);
    }
}
