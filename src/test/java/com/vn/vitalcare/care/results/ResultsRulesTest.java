package com.vn.vitalcare.care.results;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.care.appointment.service.AppointmentService;
import com.vn.vitalcare.care.customer.service.CustomerService;
import com.vn.vitalcare.care.notification.Notifier;
import com.vn.vitalcare.care.staff.service.EmployeeService;
import com.vn.vitalcare.entity.Appointment;
import com.vn.vitalcare.entity.Customer;
import com.vn.vitalcare.entity.Employee;
import com.vn.vitalcare.entity.ProfessionalLicense;
import com.vn.vitalcare.entity.ResultReturn;
import com.vn.vitalcare.identity.rowlevel.service.RowLevelSecurity;
import com.vn.vitalcare.share.exception.FieldValidationException;
import com.vn.vitalcare.share.storage.FileStorage;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

/** BF-07 result returns and FE-17 licenses: the rules that decide what a patient or a clinic sees. */
class ResultsRulesTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 11, 3, 9, 0, 0, 0, ZoneOffset.ofHours(7));
    private static final Clock CLOCK = Clock.fixed(Instant.from(NOW), ZoneOffset.UTC);

    @Test
    @DisplayName("A license is valid, expiring within 60 days, or expired; one with no end date is always valid")
    void validity() {
        LocalDate today = LocalDate.of(2026, 11, 3);
        ProfessionalLicense l = new ProfessionalLicense();
        assertThat(l.validity(today, 60)).isEqualTo("VALID");
        l.setExpiresOn(today.plusDays(61));
        assertThat(l.validity(today, 60)).isEqualTo("VALID");
        l.setExpiresOn(today.plusDays(60));
        assertThat(l.validity(today, 60)).isEqualTo("EXPIRING");
        l.setExpiresOn(today);
        assertThat(l.validity(today, 60)).isEqualTo("EXPIRING");
        l.setExpiresOn(today.minusDays(1));
        assertThat(l.validity(today, 60)).isEqualTo("EXPIRED");
    }

    @Test
    @DisplayName("Only a PDF, PNG or JPEG is kept as proof, told by its bytes and not its name")
    void sniff() {
        assertThat(Licenses.sniff("%PDF-1.7".getBytes())).isEqualTo("application/pdf");
        assertThat(Licenses.sniff(new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10})).isEqualTo("image/png");
        assertThat(Licenses.sniff(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0})).isEqualTo("image/jpeg");
        assertThat(Licenses.sniff("<html><script>".getBytes())).isNull();
    }

    @Test
    @DisplayName("A license cannot be issued in the future nor expire before it was issued")
    void licenseDates() {
        EmployeeService employees = mock(EmployeeService.class);
        Employee me = new Employee();
        ReflectionTestUtils.setField(me, "id", 11L);
        when(employees.getForCurrentUser()).thenReturn(me);
        ProfessionalLicenseRepository repo = mock(ProfessionalLicenseRepository.class);
        when(repo.persist(any(ProfessionalLicense.class))).thenAnswer(c -> c.getArgument(0));
        Licenses licenses = new Licenses(repo, employees, mock(FileStorage.class), mock(RowLevelSecurity.class),
                mock(ApplicationEventPublisher.class), CLOCK);
        LocalDate today = LocalDate.of(2026, 11, 3);

        assertThatThrownBy(() -> licenses.create(new Licenses.LicenseRequest("LICENSE", "CCHN", "001", "Sở Y tế",
                today.plusDays(1), null))).isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> licenses.create(new Licenses.LicenseRequest("LICENSE", "CCHN", "001", "Sở Y tế",
                today.minusYears(1), today.minusYears(1)))).isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> licenses.create(new Licenses.LicenseRequest("DIPLOMA", "CCHN", "001", "Sở Y tế",
                today.minusYears(1), null))).isInstanceOf(FieldValidationException.class);
        assertThat(licenses.create(new Licenses.LicenseRequest("certificate", " CME ", "002", "Bộ Y tế",
                today.minusYears(1), today.plusYears(1))).getKind()).isEqualTo("CERTIFICATE");
    }

    @Test
    @DisplayName("A return still scheduled a day after its time is missed, and the patient is told to rebook")
    void missed() {
        ResultReturnRepository repo = mock(ResultReturnRepository.class);
        Notifier notifier = mock(Notifier.class);
        ResultReturns returns = new ResultReturns(repo, mock(com.vn.vitalcare.care.encounter.repository.ClinicalEncounterRepository.class), mock(AppointmentService.class), mock(CustomerService.class),
                mock(RowLevelSecurity.class), notifier, mock(ApplicationEventPublisher.class), CLOCK);
        ResultReturn r = new ResultReturn();
        Appointment visit = new Appointment();
        ReflectionTestUtils.setField(visit, "id", 103L);
        r.setAppointment(visit);
        r.setCustomer(new Customer());
        r.setStatus(ResultReturn.SCHEDULED);
        r.setScheduledAt(NOW.minusDays(2));
        when(repo.findOverdue(any())).thenReturn(List.of(r));

        returns.markMissed();

        assertThat(r.getStatus()).isEqualTo(ResultReturn.MISSED);
        assertThat(r.getMissedAt()).isAtSameInstantAs(NOW);
    }

    @Test
    @DisplayName("A return is scheduled for an examined visit, in the future, and once while one is open")
    void scheduling() {
        ResultReturnRepository repo = mock(ResultReturnRepository.class);
        AppointmentService appointments = mock(AppointmentService.class);
        ResultReturns returns = new ResultReturns(repo, mock(com.vn.vitalcare.care.encounter.repository.ClinicalEncounterRepository.class),
                appointments, mock(CustomerService.class), mock(RowLevelSecurity.class), mock(Notifier.class),
                mock(ApplicationEventPublisher.class), CLOCK);
        Appointment visit = new Appointment();
        ReflectionTestUtils.setField(visit, "id", 116L);
        when(appointments.get(116L)).thenReturn(visit);

        ReflectionTestUtils.setField(visit, "status", com.vn.vitalcare.entity.AppointmentStatus.CANCELLED);
        assertThatThrownBy(() -> returns.schedule(116L, NOW.plusDays(1), null))
                .isInstanceOf(com.vn.vitalcare.share.exception.ConflictException.class);
        ReflectionTestUtils.setField(visit, "status", com.vn.vitalcare.entity.AppointmentStatus.COMPLETED);
        assertThatThrownBy(() -> returns.schedule(116L, NOW.minusMinutes(1), null))
                .isInstanceOf(FieldValidationException.class);
        when(repo.hasOpen(116L)).thenReturn(true);
        assertThatThrownBy(() -> returns.schedule(116L, NOW.plusDays(1), null))
                .isInstanceOf(com.vn.vitalcare.share.exception.ConflictException.class)
                .hasMessageContaining("reschedule");
    }
}
