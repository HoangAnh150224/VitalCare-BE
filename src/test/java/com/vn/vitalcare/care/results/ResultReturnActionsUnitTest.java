package com.vn.vitalcare.care.results;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.care.appointment.service.AppointmentService;
import com.vn.vitalcare.care.customer.service.CustomerService;
import com.vn.vitalcare.care.encounter.repository.ClinicalEncounterRepository;
import com.vn.vitalcare.care.notification.Notifier;
import com.vn.vitalcare.entity.Appointment;
import com.vn.vitalcare.entity.ClinicalEncounter;
import com.vn.vitalcare.entity.Customer;
import com.vn.vitalcare.entity.ResultReturn;
import com.vn.vitalcare.identity.rowlevel.service.RowLevelSecurity;
import com.vn.vitalcare.share.exception.ConflictException;
import com.vn.vitalcare.share.exception.FieldValidationException;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

/** FE-22 Review Result: the patient comes back for a result — arrive, reschedule, cancel. Report-5.1, sheet BE_Gaps. */
class ResultReturnActionsUnitTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 11, 22, 3, 0, 0, 0, ZoneOffset.UTC);
    private ResultReturnRepository repo;
    private ClinicalEncounterRepository encounters;
    private Notifier notifier;
    private ResultReturns returns;

    @BeforeEach
    void setUp() {
        repo = mock(ResultReturnRepository.class);
        encounters = mock(ClinicalEncounterRepository.class);
        notifier = mock(Notifier.class);
        RowLevelSecurity rowLevel = mock(RowLevelSecurity.class);
        when(rowLevel.byId(any(), any(), any())).thenReturn((root, query, cb) -> null);
        when(repo.persist(any(ResultReturn.class))).thenAnswer(i -> i.getArgument(0));
        returns = new ResultReturns(repo, encounters, mock(AppointmentService.class), mock(CustomerService.class), rowLevel,
                notifier, mock(ApplicationEventPublisher.class), Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));
    }

    private ResultReturn existing(String status) {
        ResultReturn r = new ResultReturn();
        Appointment visit = new Appointment();
        ReflectionTestUtils.setField(visit, "id", 103L);
        r.setAppointment(visit);
        r.setCustomer(new Customer());
        r.setStatus(status);
        when(repo.findOne(any(Specification.class))).thenReturn(Optional.of(r));
        return r;
    }

    @Test void rr01_arrivedOnAScheduledOrMissedReturn() {
        ResultReturn r = existing(ResultReturn.MISSED);
        returns.arrive(4L);
        assertEquals(ResultReturn.ARRIVED, r.getStatus());
        assertNotNull(r.getArrivedAt());
    }

    @Test void rr02_aClosedReturnCannotBeArrivedAgain() {
        existing(ResultReturn.ARRIVED);
        assertEquals("This return is already closed", assertThrows(ConflictException.class, () -> returns.arrive(4L)).getMessage());
        existing(ResultReturn.CANCELLED);
        assertThrows(ConflictException.class, () -> returns.arrive(4L));
    }

    @Test void rr03_rescheduleToTheFuture_reopensAMissedOne_andTellsThePatient() {
        ResultReturn r = existing(ResultReturn.MISSED);
        r.setMissedAt(NOW.minusDays(1));
        returns.reschedule(4L, NOW.plusDays(2));
        assertEquals(ResultReturn.SCHEDULED, r.getStatus());
        assertEquals(NOW.plusDays(2), r.getScheduledAt());
        assertNull(r.getMissedAt());
        verify(notifier).notify(any(), any(), eq("appointment"), eq("Đổi lịch trả kết quả"), anyString(), eq("/my-appointments/show/103"));
    }

    @Test void rr04_rescheduleIntoThePastOrAClosedOneIsRefused() {
        existing(ResultReturn.SCHEDULED);
        assertEquals("Choose a time in the future",
                assertThrows(FieldValidationException.class, () -> returns.reschedule(4L, NOW)).getMessage());
        existing(ResultReturn.ARRIVED);
        assertThrows(ConflictException.class, () -> returns.reschedule(4L, NOW.plusDays(1)));
    }

    @Test void rr05_cancelUnlessThePatientAlreadyCame() {
        ResultReturn r = existing(ResultReturn.SCHEDULED);
        returns.cancel(4L);
        assertEquals(ResultReturn.CANCELLED, r.getStatus());
        existing(ResultReturn.ARRIVED);
        assertEquals("The patient already came", assertThrows(ConflictException.class, () -> returns.cancel(4L)).getMessage());
    }

    @Test void rr06_fromAnEncounterWithoutAVisit_orOutOfScope() {
        when(encounters.findOne(any(Specification.class))).thenReturn(Optional.of(new ClinicalEncounter()));
        assertEquals("This encounter has no visit to return to", assertThrows(ConflictException.class,
                () -> returns.scheduleForEncounter(20L, NOW.plusDays(1), null)).getMessage());
        when(encounters.findOne(any(Specification.class))).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> returns.scheduleForEncounter(20L, NOW.plusDays(1), null));
    }

    @Test void rr07_anUnknownReturnIsNotFound() {
        when(repo.findOne(any(Specification.class))).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> returns.arrive(9L));
    }
}
