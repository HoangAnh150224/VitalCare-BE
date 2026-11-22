package com.vn.vitalcare.care.billing.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.entity.MedicalService;
import com.vn.vitalcare.entity.ServiceCategory;
import com.vn.vitalcare.entity.ServiceStatus;
import com.vn.vitalcare.share.exception.ConflictException;
import com.vn.vitalcare.share.exception.FieldValidationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** FE-22 Medical Service Catalog: MedicalServiceCatalog + MedicalServiceRequest. Report-5.1, sheet BE_Services. */
class MedicalServiceCatalogUnitTest {

    private MedicalServiceRepository repository;
    private MedicalServiceCatalog catalog;
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @BeforeEach
    void setUp() {
        repository = mock(MedicalServiceRepository.class);
        catalog = new MedicalServiceCatalog(repository);
        when(repository.save(any(MedicalService.class))).thenAnswer(i -> i.getArgument(0));
    }

    private static MedicalServiceRequest req(String code, String description, BigDecimal price, Integer minutes, ServiceStatus status) {
        return new MedicalServiceRequest(code, " Xét nghiệm máu ", description, price, ServiceCategory.LAB, minutes, status);
    }

    private static MedicalService live(ServiceStatus status, ServiceCategory category) {
        MedicalService s = new MedicalService();
        s.setStatus(status);
        s.setCategory(category);
        return s;
    }

    private Set<String> errors(MedicalServiceRequest r) {
        return validator.validate(r).stream().map(v -> v.getMessage()).collect(Collectors.toSet());
    }

    @Test void ms01_createdTrimmed() {
        MedicalService s = catalog.create(req(" XN-01 ", " Công thức máu ", new BigDecimal("120000"), 60, ServiceStatus.ACTIVE));
        assertEquals("XN-01", s.getCode());
        assertEquals("Xét nghiệm máu", s.getName());
        assertEquals("Công thức máu", s.getDescription());
        assertEquals(ServiceStatus.ACTIVE, s.getStatus());
    }

    @Test void ms02_descriptionAndStatusRequiredOnCreate() {
        assertEquals("Vui lòng nhập mô tả dịch vụ.", assertThrows(FieldValidationException.class,
                () -> catalog.create(req("XN-01", "  ", BigDecimal.ONE, 0, ServiceStatus.ACTIVE))).getMessage());
        assertEquals("Vui lòng chọn trạng thái.", assertThrows(FieldValidationException.class,
                () -> catalog.create(req("XN-01", "Mô tả", BigDecimal.ONE, 0, null))).getMessage());
        verify(repository, never()).save(any());
    }

    @Test void ms03_codeOfAnotherServiceRefused() {
        when(repository.codeTaken("XN-01", null)).thenReturn(true);
        FieldValidationException e = assertThrows(FieldValidationException.class,
                () -> catalog.create(req("XN-01", "Mô tả", BigDecimal.ONE, 0, ServiceStatus.ACTIVE)));
        assertEquals("code", e.field());
        assertEquals("Another service already has that code", e.getMessage());
    }

    @Test void ms04_updateWithoutStatusKeepsIt_blankDescriptionClears() {
        MedicalService s = live(ServiceStatus.INACTIVE, ServiceCategory.LAB);
        when(repository.findLive(3L)).thenReturn(Optional.of(s));
        catalog.update(3L, req("XN-02", " ", new BigDecimal("90000"), 30, null));
        assertEquals(ServiceStatus.INACTIVE, s.getStatus());
        assertNull(s.getDescription());
        assertEquals(new BigDecimal("90000"), s.getPrice());
    }

    @Test void ms05_requestLimits() {
        assertEquals(Set.of(), errors(req("C", null, new BigDecimal("0"), 43200, null)));
        assertEquals(Set.of("The price cannot be negative"), errors(req("C", null, new BigDecimal("-1"), 0, null)));
        assertEquals(Set.of("The price is a whole number of đồng"), errors(req("C", null, new BigDecimal("1.5"), 0, null)));
        assertEquals(Set.of("The turnaround is at most 30 days"), errors(req("C", null, BigDecimal.ONE, 43201, null)));
        assertEquals(Set.of("The turnaround cannot be negative"), errors(req("C", null, BigDecimal.ONE, -1, null)));
        assertEquals(Set.of("Vui lòng nhập mã dịch vụ."), errors(req(" ", null, BigDecimal.ONE, 0, null)));
    }

    @Test void ms06_orderableIsActiveAndNotTheExaminationFee() {
        MedicalService lab = live(ServiceStatus.ACTIVE, ServiceCategory.LAB);
        when(repository.findLive(1L)).thenReturn(Optional.of(lab));
        when(repository.findLive(2L)).thenReturn(Optional.of(live(ServiceStatus.INACTIVE, ServiceCategory.LAB)));
        when(repository.findLive(3L)).thenReturn(Optional.of(live(ServiceStatus.ACTIVE, ServiceCategory.CONSULTATION)));
        assertSame(lab, catalog.orderable(1L));
        assertEquals("That service is no longer offered",
                assertThrows(FieldValidationException.class, () -> catalog.orderable(2L)).getMessage());
        assertEquals("The examination fee is billed on its own; pick a test or procedure",
                assertThrows(FieldValidationException.class, () -> catalog.orderable(3L)).getMessage());
        assertEquals("Pick a service from the catalogue",
                assertThrows(FieldValidationException.class, () -> catalog.orderable(4L)).getMessage());
    }

    @Test void ms07_noActiveExaminationFee() {
        when(repository.findActiveByCategory(ServiceCategory.CONSULTATION)).thenReturn(List.of());
        assertEquals("The catalogue has no active examination fee; add one before concluding visits",
                assertThrows(ConflictException.class, () -> catalog.consultation()).getMessage());
    }
}
