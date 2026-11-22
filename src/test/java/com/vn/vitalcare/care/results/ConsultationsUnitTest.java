package com.vn.vitalcare.care.results;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.care.encounter.repository.ClinicalEncounterRepository;
import com.vn.vitalcare.care.encounter.service.ClinicalEncounterService;
import com.vn.vitalcare.entity.ClinicalEncounter;
import com.vn.vitalcare.entity.EncounterStatus;
import com.vn.vitalcare.identity.rowlevel.service.RowLevelSecurity;
import com.vn.vitalcare.share.exception.FieldValidationException;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.jpa.domain.Specification;

/** BF-07 / FE-22 Review Result and Consultation: Consultations.consult. Report-5.1, sheet BE_Gaps. */
class ConsultationsUnitTest {

    private static final Instant NOW = Instant.parse("2026-11-22T03:00:00Z");
    private ClinicalEncounterRepository encounters;
    private ClinicalEncounterService visits;
    private Consultations service;
    private ClinicalEncounter encounter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        encounters = mock(ClinicalEncounterRepository.class);
        visits = mock(ClinicalEncounterService.class);
        ObjectProvider<ClinicalEncounterService> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(visits);
        RowLevelSecurity rowLevel = mock(RowLevelSecurity.class);
        when(rowLevel.byId(any(), any(), any())).thenReturn((root, query, cb) -> null);
        service = new Consultations(encounters, rowLevel, mock(ApplicationEventPublisher.class),
                Clock.fixed(NOW, ZoneOffset.UTC), provider);
        encounter = new ClinicalEncounter();
        when(encounters.findOne(any(Specification.class))).thenReturn(Optional.of(encounter));
    }

    @Test void cs01_conclusionRecorded_andTheOpenVisitIsConcluded() {
        Map<String, Object> body = service.consult(20L, "  Viêm phế quản cấp  ", "  Tái khám sau 7 ngày  ");
        assertEquals("Viêm phế quản cấp", body.get("finalConclusion"));
        assertEquals("Tái khám sau 7 ngày", body.get("consultationNotes"));
        assertEquals(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC), body.get("consultedAt"));
        verify(encounters).persist(encounter);
        verify(visits).complete(20L);
    }

    @Test void cs02_alreadyCompletedVisitIsNotConcludedAgain_blankNotesNone() {
        encounter.setStatus(EncounterStatus.COMPLETED);
        Map<String, Object> body = service.consult(20L, "Ổn định", "   ");
        assertNull(body.get("consultationNotes"));
        verify(visits, never()).complete(any());
    }

    @Test void cs03_conclusionRequired() {
        assertEquals("Write the final conclusion",
                assertThrows(FieldValidationException.class, () -> service.consult(20L, "  ", null)).getMessage());
        verify(encounters, never()).persist(any());
    }

    @Test void cs04_encounterOutsideMyScopeIs404() {
        when(encounters.findOne(any(Specification.class))).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> service.consult(99L, "x", null));
    }
}
