package com.vn.vitalcare.care.medicalrecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.care.customer.service.CustomerService;
import com.vn.vitalcare.care.medicalrecord.dto.MedicalRecordRequest;
import com.vn.vitalcare.care.medicalrecord.dto.MedicalRecordResponse;
import com.vn.vitalcare.care.medicalrecord.dto.MonitoringNoteRequest;
import com.vn.vitalcare.care.medicalrecord.repository.MedicalRecordRepository;
import com.vn.vitalcare.care.medicalrecord.service.impl.MedicalRecordServiceImpl;
import com.vn.vitalcare.entity.Customer;
import com.vn.vitalcare.entity.MedicalRecord;
import com.vn.vitalcare.entity.PatientActivationSource;
import com.vn.vitalcare.share.exception.ConflictException;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** FE-08 Medical Record Overview / Health History: MedicalRecordServiceImpl. Report-5.1, sheet BE_MedicalRecord. */
class MedicalRecordUnitTest {

    private MedicalRecordRepository repository;
    private CustomerService customers;
    private MedicalRecordServiceImpl service;
    private Customer patient;
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @BeforeEach
    void setUp() {
        repository = mock(MedicalRecordRepository.class);
        customers = mock(CustomerService.class);
        service = new MedicalRecordServiceImpl(repository, customers);
        patient = new Customer();
        ReflectionTestUtils.setField(patient, "id", 1L);
        patient.activateAsPatient(PatientActivationSource.values()[0], 9L, OffsetDateTime.now());
        when(customers.get(1L)).thenReturn(patient);
        when(customers.getForUpdate(1L)).thenReturn(patient);
        when(repository.saveAndFlush(any(MedicalRecord.class))).thenAnswer(i -> i.getArgument(0));
    }

    private static MedicalRecordRequest req(String blood, String allergies, Integer version) {
        return new MedicalRecordRequest(blood, allergies, "Tăng huyết áp", null, "  ", "Theo dõi SpO₂", version);
    }

    @Test void mr01_noRecordYetReadsEmpty() {
        when(repository.findByCustomerIdAndDeletedAtIsNull(1L)).thenReturn(Optional.empty());
        MedicalRecordResponse r = service.get(1L);
        assertEquals(1L, r.customerId());
        assertNull(r.bloodType());
        assertNull(r.medicalHistory());
    }

    @Test void mr02_patientOutsideMyScopeIs404() {
        when(customers.get(2L)).thenThrow(new ResourceNotFoundException("Customer", 2L));
        assertThrows(ResourceNotFoundException.class, () -> service.get(2L));
        verify(repository, never()).findByCustomerIdAndDeletedAtIsNull(any());
    }

    @Test void mr03_firstSaveCreates_trimmed_blankBecomesEmpty() {
        when(repository.findByCustomerIdAndDeletedAtIsNull(1L)).thenReturn(Optional.empty());
        MedicalRecordResponse r = service.save(1L, req(" O+ ", "Penicillin", null));
        assertEquals("O+", r.bloodType());
        assertEquals("Penicillin", r.allergies());
        assertEquals("Tăng huyết áp", r.medicalHistory());
        assertNull(r.currentMedications());
    }

    @Test void mr04_neutralCustomerHasNoRecord() {
        Customer neutral = new Customer();
        when(customers.getForUpdate(3L)).thenReturn(neutral);
        ConflictException e = assertThrows(ConflictException.class, () -> service.save(3L, req("A", null, null)));
        assertEquals("Only a patient has a medical record; activate them first", e.getMessage());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test void mr05_savingOverANewerVersionIsRefused() {
        MedicalRecord existing = new MedicalRecord();
        ReflectionTestUtils.setField(existing, "id", 7L);
        ReflectionTestUtils.setField(existing, "version", 3);
        when(repository.findByCustomerIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(existing));
        ConflictException e = assertThrows(ConflictException.class, () -> service.save(1L, req("B", null, 2)));
        assertEquals("This record was changed by somebody else; reload it and try again", e.getMessage());
        assertEquals("B", service.save(1L, req("B", null, 3)).bloodType());
    }

    @Test void mr06_lengthLimits() {
        var v = validator.validate(new MedicalRecordRequest("x".repeat(33), "a".repeat(5001), null, null, null, null, null))
                .stream().map(x -> x.getMessage()).collect(Collectors.toSet());
        assertEquals(java.util.Set.of("Blood type must be at most 32 characters", "Allergies must be at most 5000 characters"), v);
        assertEquals(0, validator.validate(new MedicalRecordRequest("x".repeat(32), "a".repeat(5000), null, null, null, null, null)).size());
    }

    @Test void mn01_noteRequired_atMost5000() {
        assertEquals(java.util.Set.of("A note is required"), validator.validate(new MonitoringNoteRequest("   "))
                .stream().map(x -> x.getMessage()).collect(Collectors.toSet()));
        assertEquals(java.util.Set.of("A note must be at most 5000 characters"), validator.validate(new MonitoringNoteRequest("n".repeat(5001)))
                .stream().map(x -> x.getMessage()).collect(Collectors.toSet()));
        assertEquals(0, validator.validate(new MonitoringNoteRequest("n".repeat(5000))).size());
    }
}
