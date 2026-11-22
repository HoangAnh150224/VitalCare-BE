package com.vn.vitalcare.care.results;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.care.appointment.service.AppointmentService;
import com.vn.vitalcare.care.customer.service.CustomerService;
import com.vn.vitalcare.entity.Appointment;
import com.vn.vitalcare.entity.AppointmentStatus;
import com.vn.vitalcare.entity.Customer;
import com.vn.vitalcare.entity.PatientFeedback;
import com.vn.vitalcare.identity.rowlevel.service.RowLevelSecurity;
import com.vn.vitalcare.share.exception.ConflictException;
import com.vn.vitalcare.share.exception.FieldValidationException;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

/** FE-07 Submit Feedback: Feedback.give / edit. Report-5.1, sheet BE_Patients. */
class FeedbackSubmitUnitTest {

    private PatientFeedbackRepository repository;
    private AppointmentService appointments;
    private CustomerService customers;
    private Feedback service;
    private Customer me;
    private Appointment visit;

    @BeforeEach
    void setUp() {
        repository = mock(PatientFeedbackRepository.class);
        appointments = mock(AppointmentService.class);
        customers = mock(CustomerService.class);
        service = new Feedback(repository, appointments, customers, mock(RowLevelSecurity.class),
                mock(ApplicationEventPublisher.class));
        me = new Customer();
        ReflectionTestUtils.setField(me, "id", 1L);
        when(customers.getForCurrentUser()).thenReturn(me);
        when(customers.profilesOf(me)).thenReturn(List.of(me));
        when(customers.ownProfile(me, 1L)).thenReturn(me);
        visit = new Appointment();
        ReflectionTestUtils.setField(visit, "id", 10L);
        visit.setCustomer(me);
        ReflectionTestUtils.setField(visit, "status", AppointmentStatus.COMPLETED);
        when(appointments.getOwn(me, 10L)).thenReturn(visit);
        when(repository.persist(any(PatientFeedback.class))).thenAnswer(i -> {
            PatientFeedback f = i.getArgument(0);
            ReflectionTestUtils.setField(f, "id", 5L);
            return f;
        });
        when(repository.findWithParties(5L)).thenAnswer(i -> Optional.of(saved()));
    }

    private PatientFeedback last;

    private PatientFeedback saved() {
        return last;
    }

    private PatientFeedback give(Integer rating, String comment) {
        when(repository.persist(any(PatientFeedback.class))).thenAnswer(i -> {
            last = i.getArgument(0);
            ReflectionTestUtils.setField(last, "id", 5L);
            return last;
        });
        return service.give(10L, rating, comment);
    }

    @Test void fb01_completedVisitRated_commentTrimmed() {
        PatientFeedback f = give(5, "  Bác sĩ tận tình  ");
        assertEquals(5, f.getRating());
        assertEquals("Bác sĩ tận tình", f.getComment());
        assertEquals(me, f.getCustomer());
        verify(repository).flush();
    }

    @Test void fb02_visitNotCompletedRefused() {
        ReflectionTestUtils.setField(visit, "status", AppointmentStatus.CHECKED_IN);
        ConflictException e = assertThrows(ConflictException.class, () -> give(4, null));
        assertEquals("Feedback is given on a completed visit", e.getMessage());
    }

    @Test void fb03_secondFeedbackOnTheSameVisitRefused() {
        when(repository.existsForVisit(10L)).thenReturn(true);
        ConflictException e = assertThrows(ConflictException.class, () -> give(4, null));
        assertEquals("You already gave feedback on this visit; edit it instead", e.getMessage());
    }

    @Test void fb04_someoneElsesVisitIs404() {
        when(appointments.getOwn(me, 10L)).thenThrow(new ResourceNotFoundException("Appointment", 10L));
        assertThrows(ResourceNotFoundException.class, () -> give(4, null));
        verify(repository, never()).persist(any());
    }

    @Test void fb05_ratingOutsideOneToFiveRefused() {
        assertEquals("Rate from 1 to 5", assertThrows(FieldValidationException.class, () -> give(0, null)).getMessage());
        assertThrows(FieldValidationException.class, () -> give(6, null));
        assertThrows(FieldValidationException.class, () -> give(null, null));
        assertEquals(1, give(1, null).getRating());
    }

    @Test void fb06_comment2000Accepted_2001Refused_blankBecomesEmpty() {
        assertEquals(2000, give(3, "x".repeat(2000)).getComment().length());
        when(repository.existsForVisit(10L)).thenReturn(false);
        assertEquals("At most 2000 characters",
                assertThrows(FieldValidationException.class, () -> give(3, "x".repeat(2001))).getMessage());
        assertNull(give(3, "   ").getComment());
    }

    @Test void fb07_editOwnFeedback_othersAre404() {
        PatientFeedback mine = new PatientFeedback();
        ReflectionTestUtils.setField(mine, "id", 5L);
        mine.setRating(2);
        when(repository.findForProfiles(List.of(1L))).thenReturn(List.of(mine));
        service.edit(5L, 4, "Đã đỡ hơn");
        assertEquals(4, mine.getRating());
        assertEquals("Đã đỡ hơn", mine.getComment());
        assertThrows(ResourceNotFoundException.class, () -> service.edit(6L, 4, null));
    }
}
