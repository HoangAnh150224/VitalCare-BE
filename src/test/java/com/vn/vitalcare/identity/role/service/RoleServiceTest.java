package com.vn.vitalcare.identity.role.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.identity.permission.service.PermissionService;
import com.vn.vitalcare.identity.role.dto.RolePatchRequest;
import com.vn.vitalcare.identity.role.entity.Role;
import com.vn.vitalcare.identity.role.repository.RoleRepository;
import com.vn.vitalcare.share.audit.AuditEvent;
import com.vn.vitalcare.share.exception.ConflictException;
import com.vn.vitalcare.share.exception.FieldValidationException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/** KAN-57 / KAN-58 / KAN-54 / KAN-61: role name rule, the ADMIN refusal, and the audit trail. */
class RoleServiceTest {

    private RoleRepository repository;
    private ApplicationEventPublisher events;
    private RoleService service;

    @BeforeEach
    void setUp() {
        repository = mock(RoleRepository.class);
        events = mock(ApplicationEventPublisher.class);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        service = new RoleService(repository, mock(PermissionService.class), events);
    }

    private Role stub(String code, boolean system) {
        Role role = new Role(code, "Tên cũ", null, system);
        when(repository.findById(1L)).thenReturn(Optional.of(role));
        return role;
    }

    @Test
    @DisplayName("RULE-14/15: a name of only whitespace is refused, nothing saved")
    void blankNameRefused() {
        stub("GUEST", true);
        FieldValidationException e = assertThrows(FieldValidationException.class,
                () -> service.update(1L, new RolePatchRequest(null, "    ", null, null)));
        assertEquals("Tên vai trò không hợp lệ. Vui lòng thử lại.", e.getMessage());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("RULE-14: the name is stored trimmed and the edit goes to the audit log")
    void updateTrimsAndAudits() {
        Role role = stub("GUEST", true);
        service.update(1L, new RolePatchRequest(null, "  Khách  ", null, null));

        assertEquals("Khách", role.getName());
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(captor.capture());
        AuditEvent event = (AuditEvent) captor.getValue();
        assertEquals("roles", event.entityType());
        assertEquals("update", event.action());
        assertEquals("Tên cũ", event.before().get("name"));
        assertEquals("Khách", event.after().get("name"));
    }

    @Test
    @DisplayName("RULE-27: deleting ADMIN is refused in Vietnamese")
    void adminDeleteRefused() {
        stub("ADMIN", true);
        ConflictException e = assertThrows(ConflictException.class, () -> service.delete(1L));
        assertEquals("Không thể xoá vai trò ADMIN.", e.getMessage());
        verify(repository, never()).delete(any(Role.class));
    }

    @Test
    @DisplayName("RULE-05: deleting a custom role is in the audit log")
    void deleteAudited() {
        stub("CUSTOM", false);
        service.delete(1L);
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(events, atLeastOnce()).publishEvent(captor.capture());
        assertEquals(1, captor.getAllValues().stream()
                .filter(e -> e instanceof AuditEvent a && "delete".equals(a.action())).count());
    }
}
