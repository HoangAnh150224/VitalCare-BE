package com.vn.vitalcare.care.medicalrecord.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.care.customer.service.CustomerService;
import com.vn.vitalcare.care.medicalrecord.dto.MonitoringNoteRequest;
import com.vn.vitalcare.care.medicalrecord.repository.MonitoringNoteRepository;
import com.vn.vitalcare.care.medicalrecord.service.impl.MonitoringNoteServiceImpl;
import com.vn.vitalcare.entity.Customer;
import com.vn.vitalcare.entity.MonitoringNote;
import com.vn.vitalcare.identity.user.service.UserService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;

/** SRS 3.9.3: a note is signed by whoever wrote it, and changed by them alone. */
class MonitoringNoteServiceTest {

    private MonitoringNoteRepository repository;
    private MonitoringNoteService service;
    private MonitoringNote nursesNote;

    @BeforeEach
    void setUp() {
        repository = mock(MonitoringNoteRepository.class);
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        CustomerService customers = mock(CustomerService.class);
        when(customers.get(7L)).thenReturn(new Customer());
        UserService users = mock(UserService.class);
        when(users.getMany(any())).thenReturn(List.of());
        service = new MonitoringNoteServiceImpl(repository, customers, users);

        // Written by user 12, the nurse.
        nursesNote = new MonitoringNote(new Customer(), "Đã liên hệ bệnh nhân.");
        ReflectionTestUtils.setField(nursesNote, "createdById", 12L);
        when(repository.findByIdAndCustomerIdAndDeletedAtIsNull(3L, 7L)).thenReturn(Optional.of(nursesNote));
        when(repository.findByCustomerIdAndDeletedAtIsNullOrderByCreatedAtDesc(7L)).thenReturn(List.of(nursesNote));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("the author changes their own note; anybody else is refused and the note stays as written")
    void onlyTheAuthorEdits() {
        signIn(11L);
        assertThrows(AccessDeniedException.class, () -> service.update(7L, 3L, new MonitoringNoteRequest("Sửa")));
        assertEquals("Đã liên hệ bệnh nhân.", nursesNote.getContent());
        assertFalse(service.list(7L).getFirst().editable());

        signIn(12L);
        assertEquals("Đã liên hệ, thiết bị đã kết nối lại.",
                service.update(7L, 3L, new MonitoringNoteRequest("  Đã liên hệ, thiết bị đã kết nối lại. ")).content());
        assertTrue(service.list(7L).getFirst().editable());
    }

    private static void signIn(long userId) {
        Jwt token = Jwt.withTokenValue("test").header("alg", "none").subject(String.valueOf(userId))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(token));
    }
}
