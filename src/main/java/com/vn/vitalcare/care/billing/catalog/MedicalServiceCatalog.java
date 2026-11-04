package com.vn.vitalcare.care.billing.catalog;

import com.vn.vitalcare.entity.MedicalService;
import com.vn.vitalcare.entity.ServiceCategory;
import com.vn.vitalcare.entity.ServiceStatus;
import com.vn.vitalcare.share.exception.ConflictException;
import com.vn.vitalcare.share.exception.FieldValidationException;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The service catalogue and its prices: one for every clinic, kept by the
 * system administrator. A service is never deleted, only made inactive, so the
 * orders and bills that used it keep pointing at it.
 */
@Service
@Transactional(readOnly = true)
public class MedicalServiceCatalog {

    private final MedicalServiceRepository repository;

    public MedicalServiceCatalog(MedicalServiceRepository repository) {
        this.repository = repository;
    }

    public List<MedicalService> list() {
        return repository.findLive();
    }

    public MedicalService get(Long id) {
        return repository.findLive(id).orElseThrow(() -> new ResourceNotFoundException("Medical service", id));
    }

    /** What a doctor may order now: active, and not the examination fee itself. */
    public MedicalService orderable(Long id) {
        MedicalService service = repository.findLive(id)
                .orElseThrow(() -> new FieldValidationException("serviceId", "Pick a service from the catalogue"));
        if (service.getStatus() != ServiceStatus.ACTIVE) {
            throw new FieldValidationException("serviceId", "That service is no longer offered");
        }
        if (service.getCategory() == ServiceCategory.CONSULTATION) {
            throw new FieldValidationException("serviceId", "The examination fee is billed on its own; pick a test or procedure");
        }
        return service;
    }

    /** The examination fee every bill starts with. */
    public MedicalService consultation() {
        return repository.findActiveByCategory(ServiceCategory.CONSULTATION).stream().findFirst()
                .orElseThrow(() -> new ConflictException(
                        "The catalogue has no active examination fee; add one before concluding visits"));
    }

    @Transactional
    public MedicalService create(MedicalServiceRequest request) {
        MedicalService service = new MedicalService();
        apply(service, request);
        return repository.save(service);
    }

    @Transactional
    public MedicalService update(Long id, MedicalServiceRequest request) {
        MedicalService service = get(id);
        apply(service, request);
        return repository.save(service);
    }

    private void apply(MedicalService service, MedicalServiceRequest request) {
        String code = request.code().trim();
        if (repository.codeTaken(code, service.getId())) {
            throw new FieldValidationException("code", "Another service already has that code");
        }
        service.setCode(code);
        service.setName(request.name().trim());
        service.setDescription(blankToNull(request.description()));
        service.setPrice(request.price());
        service.setCategory(request.category());
        service.setTurnaroundMinutes(request.turnaroundMinutes());
        if (request.status() != null) {
            service.setStatus(request.status());
        }
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
