package com.vn.vitalcare.care.clinic.service.impl;

import com.vn.vitalcare.care.clinic.dto.ClinicSummaryResponse;
import com.vn.vitalcare.care.clinic.ClinicProperties;
import com.vn.vitalcare.care.clinic.dto.ClinicRequest;
import com.vn.vitalcare.care.clinic.repository.ClinicRepository;
import com.vn.vitalcare.care.clinic.service.ClinicService;
import com.vn.vitalcare.entity.Clinic;
import com.vn.vitalcare.share.audit.AuditEvent;
import com.vn.vitalcare.share.exception.FieldValidationException;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
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
        if (repository.existsByClinicNameIgnoreCaseAndDeletedAtIsNull(request.name().trim())) {
            throw new FieldValidationException("name", DUPLICATE_NAME);
        }
        Clinic clinic = new Clinic();
        clinic.setClinicId(UUID.randomUUID());
        clinic.setOperatingStatus("OPERATING");
        Clinic saved = repository.save(apply(clinic, request));
        audit("create", null, saved);
        return saved;
    }

    @Override
    @Transactional
    public Clinic update(UUID id, ClinicRequest request) {
        Clinic clinic = get(id);
        if (repository.existsByClinicNameIgnoreCaseAndDeletedAtIsNullAndClinicIdNot(request.name().trim(), id)) {
            throw new FieldValidationException("name", DUPLICATE_NAME);
        }
        Map<String, Object> before = state(clinic);
        Clinic saved = repository.save(apply(clinic, request));
        audit("update", before, saved);
        return saved;
    }

    static final String DUPLICATE_NAME = "Tên cơ sở đã tồn tại. Hãy đặt tên khác.";

    /** KAN-49: a clinic's id is a UUID and the log's entity id a number, so the id rides in the state. */
    private void audit(String action, Map<String, Object> before, Clinic clinic) {
        events.publishEvent(new AuditEvent("clinics", null, action, before, state(clinic)));
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
        audit("status_change", before, saved);
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
}
