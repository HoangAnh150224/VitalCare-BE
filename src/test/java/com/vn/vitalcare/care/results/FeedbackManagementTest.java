package com.vn.vitalcare.care.results;

import com.vn.vitalcare.care.appointment.service.AppointmentService;
import com.vn.vitalcare.care.customer.service.CustomerService;
import com.vn.vitalcare.entity.PatientFeedback;
import com.vn.vitalcare.identity.rowlevel.service.RowLevelSecurity;
import com.vn.vitalcare.share.exception.FieldValidationException;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FeedbackManagementTest {
    private final PatientFeedbackRepository repository = mock(PatientFeedbackRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final RowLevelSecurity rowLevel = mock(RowLevelSecurity.class);
    private final Feedback service = new Feedback(repository, mock(AppointmentService.class), mock(CustomerService.class), rowLevel, events);

    @org.junit.jupiter.api.BeforeEach void setUpScope() {
        when(rowLevel.byId(eq(PatientFeedback.class), eq(com.vn.vitalcare.share.security.rowlevel.Action.READ), any()))
                .thenReturn((root, query, builder) -> builder.conjunction());
    }

    @Test void rejectsBlankReplyAndDeletionReasonBeforeReading() {
        assertThrows(FieldValidationException.class, () -> service.reply(1L, "  "));
        assertThrows(FieldValidationException.class, () -> service.remove(1L, "  "));
        verifyNoInteractions(repository);
    }

    @Test void inaccessibleFeedbackCannotBeChanged() {
        when(repository.findOne(any(org.springframework.data.jpa.domain.Specification.class))).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> service.reply(1L, "Reply"));
        verify(repository, never()).persist(any());
    }

    @Test void savesTrimmedReplyAndTimestamp() {
        PatientFeedback feedback = new PatientFeedback();
        when(repository.findOne(any(org.springframework.data.jpa.domain.Specification.class))).thenReturn(Optional.of(feedback));
        when(repository.persist(feedback)).thenReturn(feedback);
        service.reply(1L, "  Thank you  ");
        assertEquals("Thank you", feedback.getReply());
        assertNotNull(feedback.getRepliedAt());
        verify(events).publishEvent(any(Object.class));
    }

    @Test void deletedFeedbackRemainsReadableButCannotBeChanged() {
        PatientFeedback feedback = new PatientFeedback();
        feedback.markDeleted(null, java.time.Instant.now());
        when(repository.findOne(any(org.springframework.data.jpa.domain.Specification.class))).thenReturn(Optional.of(feedback));
        assertSame(feedback, service.get(1L));
        assertThrows(com.vn.vitalcare.share.exception.ConflictException.class, () -> service.reply(1L, "Reply"));
        assertThrows(com.vn.vitalcare.share.exception.ConflictException.class, () -> service.remove(1L, "Spam"));
        verify(repository, never()).persist(any());
    }

    @Test void removalIsSoftAndAudited() {
        PatientFeedback feedback = new PatientFeedback();
        when(repository.findOne(any(org.springframework.data.jpa.domain.Specification.class))).thenReturn(Optional.of(feedback));
        service.remove(1L, "Spam");
        assertTrue(feedback.isDeleted());
        verify(repository).persist(feedback);
        verify(repository, never()).remove(any());
        verify(events).publishEvent(any(Object.class));
    }
}
