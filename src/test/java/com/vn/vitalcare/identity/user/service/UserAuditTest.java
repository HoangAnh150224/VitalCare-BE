package com.vn.vitalcare.identity.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.identity.role.service.RoleService;
import com.vn.vitalcare.identity.token.service.RefreshTokenService;
import com.vn.vitalcare.identity.user.dto.UserPatchRequest;
import com.vn.vitalcare.identity.user.entity.User;
import com.vn.vitalcare.identity.user.entity.UserStatus;
import com.vn.vitalcare.identity.user.repository.UserRepository;
import com.vn.vitalcare.share.audit.AuditEvent;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;

/** KAN-51 / KAN-52: an admin's edit and a delete leave an audit record that says what changed. */
class UserAuditTest {

    private UserRepository repository;
    private ApplicationEventPublisher events;
    private UserService service;
    private User user;

    @BeforeEach
    void setUp() {
        repository = mock(UserRepository.class);
        events = mock(ApplicationEventPublisher.class);
        service = new UserService(repository, mock(RoleService.class), mock(RefreshTokenService.class),
                mock(PasswordEncoder.class), events);
        user = new User("+84900000009", "old@example.com", "hash", "Tên cũ", UserStatus.ACTIVE);
        org.springframework.test.util.ReflectionTestUtils.setField(user, "id", 1L);
        when(repository.findById(1L)).thenReturn(Optional.of(user));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void editRecordsEveryChangedProfileFieldAndOnlyThose() {
        service.update(1L, new UserPatchRequest("0900000009", "new@example.com", "Tên mới", null, null));

        AuditEvent update = auditEvents().stream().filter(e -> e.action().equals("update")).findFirst().orElseThrow();
        assertThat(update.entityType()).isEqualTo("users");
        // The phone was re-sent in another spelling: not a change, not recorded.
        assertThat(update.before()).isEqualTo(Map.of("email", "old@example.com", "fullName", "Tên cũ"));
        assertThat(update.after()).isEqualTo(Map.of("email", "new@example.com", "fullName", "Tên mới"));
    }

    @Test
    void editThatChangesNothingRecordsNothing() {
        service.update(1L, new UserPatchRequest(null, null, "Tên cũ", null, null));

        assertThat(auditEvents()).noneMatch(e -> e.action().equals("update"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void deleteIsSoftAndRecordsTheStateTheAccountWasLeftIn() {
        service.delete(1L);

        assertThat(user.getDeletedAt()).isNotNull();
        assertThat(user.getStatus()).isEqualTo(UserStatus.INACTIVE);
        AuditEvent delete = auditEvents().stream().filter(e -> e.action().equals("delete")).findFirst().orElseThrow();
        assertThat((Map<String, Object>) delete.before()).containsEntry("status", "ACTIVE").containsEntry("fullName", "Tên cũ");
        assertThat((Map<String, Object>) delete.after()).containsEntry("status", "INACTIVE").containsKey("deletedAt")
                .containsEntry("phone", "+84900000009");
    }

    private List<AuditEvent> auditEvents() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(events, org.mockito.Mockito.atLeast(0)).publishEvent(captor.capture());
        return captor.getAllValues().stream().filter(AuditEvent.class::isInstance).map(AuditEvent.class::cast).toList();
    }
}
