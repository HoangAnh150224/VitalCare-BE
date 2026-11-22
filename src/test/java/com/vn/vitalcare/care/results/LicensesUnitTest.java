package com.vn.vitalcare.care.results;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.care.staff.service.EmployeeService;
import com.vn.vitalcare.entity.Employee;
import com.vn.vitalcare.entity.ProfessionalLicense;
import com.vn.vitalcare.identity.rowlevel.service.RowLevelSecurity;
import com.vn.vitalcare.share.exception.FieldValidationException;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import com.vn.vitalcare.share.storage.FileStorage;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Report-5.1 BE_Gaps2: Licenses.attach / update / remove / ownFile / clinicFile (FE-17 Manage Own Professional License). */
class LicensesUnitTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-22T02:00:00Z"), ZoneOffset.UTC);
    private static final byte[] PDF = "%PDF-1.7 scan".getBytes();

    private final ProfessionalLicenseRepository repo = mock(ProfessionalLicenseRepository.class);
    private final EmployeeService employees = mock(EmployeeService.class);
    private final FileStorage storage = mock(FileStorage.class);
    private final RowLevelSecurity rowLevel = mock(RowLevelSecurity.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final Licenses licenses = new Licenses(repo, employees, storage, rowLevel, events, CLOCK);
    private ProfessionalLicense mine;

    @BeforeEach
    void setUp() {
        Employee me = new Employee();
        ReflectionTestUtils.setField(me, "id", 11L);
        when(employees.getForCurrentUser()).thenReturn(me);
        mine = new ProfessionalLicense();
        ReflectionTestUtils.setField(mine, "id", 5L);
        mine.setFileKey("old-key");
        when(repo.findOwn(11L)).thenReturn(List.of(mine));
        when(repo.persist(any(ProfessionalLicense.class))).thenAnswer(c -> c.getArgument(0));
        when(storage.store(any(InputStream.class))).thenReturn("new-key");
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("A PDF scan is stored; its type comes from its bytes, its name is made safe")
    void li01_attachPdf() {
        ProfessionalLicense saved = licenses.attach(5L, new MockMultipartFile("file", "cchn:2026?.pdf", "image/png", PDF));
        assertThat(saved.getFileKey()).isEqualTo("new-key");
        assertThat(saved.getFileType()).isEqualTo("application/pdf");
        assertThat(saved.getFileName()).isEqualTo("cchn_2026_.pdf");
        assertThat(saved.getFileSize()).isEqualTo(PDF.length);
    }

    @Test
    @DisplayName("Committed: the replaced scan is deleted; rolled back: the new scan is deleted instead")
    void li02_afterCompletionCleansUp() {
        TransactionSynchronizationManager.initSynchronization();
        licenses.attach(5L, new MockMultipartFile("file", "a.pdf", "application/pdf", PDF));
        List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
        syncs.forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
        verify(storage).delete("old-key");
        verify(storage, never()).delete("new-key");

        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.initSynchronization();
        mine.setFileKey("old-key");
        licenses.attach(5L, new MockMultipartFile("file", "a.pdf", "application/pdf", PDF));
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verify(storage).delete("new-key");
    }

    @Test
    @DisplayName("An empty file, or one that is not PDF/PNG/JPEG by its bytes, is refused and nothing is stored")
    void li03_badFileRefused() {
        assertThatThrownBy(() -> licenses.attach(5L, new MockMultipartFile("file", "a.pdf", "application/pdf", new byte[0])))
                .isInstanceOf(FieldValidationException.class).hasMessage("A file of at most 10 MB");
        assertThatThrownBy(() -> licenses.attach(5L, new MockMultipartFile("file", "a.pdf", "application/pdf", "<html>".getBytes())))
                .isInstanceOf(FieldValidationException.class).hasMessage("Only PDF, PNG or JPEG");
        verify(storage, never()).store(any(InputStream.class));
    }

    @Test
    @DisplayName("Boundary: a scan over 10 MB is refused")
    void li04_overTenMegabytes() {
        byte[] big = new byte[10 * 1024 * 1024 + 1];
        System.arraycopy(PDF, 0, big, 0, PDF.length);
        assertThatThrownBy(() -> licenses.attach(5L, new MockMultipartFile("file", "a.pdf", "application/pdf", big)))
                .isInstanceOf(FieldValidationException.class).hasMessage("A file of at most 10 MB");
    }

    @Test
    @DisplayName("Somebody else's license cannot be attached to, changed or removed: 404")
    void li05_notMine() {
        assertThatThrownBy(() -> licenses.attach(99L, new MockMultipartFile("file", "a.pdf", "application/pdf", PDF)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> licenses.update(99L, new Licenses.LicenseRequest("LICENSE", "CCHN", "1", "Sở Y tế",
                LocalDate.of(2020, 1, 1), null))).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> licenses.remove(99L)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("Update trims and saves the fields and is audited; remove marks the license deleted")
    void li06_updateAndRemove() {
        ProfessionalLicense updated = licenses.update(5L, new Licenses.LicenseRequest("license", " CCHN ", " 001 ", " Sở Y tế ",
                LocalDate.of(2020, 1, 1), LocalDate.of(2027, 1, 1)));
        assertThat(updated.getKind()).isEqualTo("LICENSE");
        assertThat(updated.getTitle()).isEqualTo("CCHN");
        assertThat(updated.getLicenseNumber()).isEqualTo("001");
        verify(events).publishEvent(any(Object.class));
        licenses.remove(5L);
        assertThat(mine.isDeleted()).isTrue();
    }

    @Test
    @DisplayName("ownFile hands back the stored scan; a license without a scan answers 404")
    void li07_ownFile() {
        mine.setFileName("a.pdf");
        mine.setFileType("application/pdf");
        mine.setFileSize(13L);
        when(storage.load("old-key")).thenReturn(new ByteArrayResource(PDF));
        Licenses.StoredFile file = licenses.ownFile(5L);
        assertThat(file.name()).isEqualTo("a.pdf");
        assertThat(file.type()).isEqualTo("application/pdf");
        mine.setFileKey(null);
        assertThatThrownBy(() -> licenses.ownFile(5L)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("clinicFile reads through the clinic's row-level scope; outside it answers 404")
    void li08_clinicFile() {
        when(rowLevel.byId(any(), any(), any())).thenReturn((root, query, cb) -> null);
        mine.setFileSize(13L);
        when(repo.findOne(any(org.springframework.data.jpa.domain.Specification.class))).thenReturn(Optional.of(mine));
        when(storage.load("old-key")).thenReturn(new ByteArrayResource(PDF));
        assertThat(licenses.clinicFile(5L).content()).isNotNull();
        when(repo.findOne(any(org.springframework.data.jpa.domain.Specification.class))).thenReturn(Optional.empty());
        assertThatThrownBy(() -> licenses.clinicFile(6L)).isInstanceOf(ResourceNotFoundException.class);
    }
}
