package com.vn.vitalcare.identity.rowlevel.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.identity.role.entity.Role;
import com.vn.vitalcare.identity.role.service.RoleService;
import com.vn.vitalcare.identity.rowlevel.dto.RoleRef;
import com.vn.vitalcare.identity.rowlevel.dto.RowLevelPolicyRequest;
import com.vn.vitalcare.identity.rowlevel.entity.PolicyKind;
import com.vn.vitalcare.identity.rowlevel.entity.RowLevelPolicy;
import com.vn.vitalcare.identity.rowlevel.repository.RowLevelPolicyAuditRepository;
import com.vn.vitalcare.identity.rowlevel.repository.RowLevelPolicyRepository;
import com.vn.vitalcare.identity.user.service.UserService;
import com.vn.vitalcare.share.audit.AuditEvent;
import com.vn.vitalcare.share.exception.ConflictException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.ObjectMapper;

/** KAN-65: a copy of an existing scope is refused; KAN-61: a new one reaches the audit log. */
class DuplicatePolicyTest {

    private static final String SCOPE = "{\"all\":[{\"op\":\"eq\",\"field\":\"id\",\"value\":{\"lit\":2}}]}";

    private final ObjectMapper mapper = new ObjectMapper();
    private RowLevelPolicyRepository repository;
    private ApplicationEventPublisher events;
    private RowLevelPolicyService service;
    private Role role;

    @BeforeEach
    void setUp() {
        repository = mock(RowLevelPolicyRepository.class);
        events = mock(ApplicationEventPublisher.class);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        RowLevelRegistry registry = mock(RowLevelRegistry.class);
        when(registry.byResource("devices"))
                .thenReturn(Optional.of(new RowLevelRegistry.Managed("devices", Object.class, null, Map.of())));
        role = mock(Role.class);
        when(role.getId()).thenReturn(3L);
        RoleService roles = mock(RoleService.class);
        when(roles.get(3L)).thenReturn(role);
        service = new RowLevelPolicyService(repository, mock(RowLevelPolicyAuditRepository.class), registry,
                mock(PolicyCompiler.class), new ScopeTreeCodec(mapper), mapper, roles,
                mock(UserService.class), events);
    }

    private RowLevelPolicyRequest request(String scope) {
        return new RowLevelPolicyRequest("SCOPE", new RoleRef(3L), "devices", "read", "Bản sao",
                "Nhóm", "Mô tả", mapper.readTree(scope), null, true);
    }

    private void existing(String scope) {
        RowLevelPolicy other = mock(RowLevelPolicy.class);
        when(other.getId()).thenReturn(9L);
        when(other.getRole()).thenReturn(role);
        when(other.getScope()).thenReturn(scope);
        when(repository.findByKindAndResourceAndAction(PolicyKind.SCOPE, "devices", "read"))
                .thenReturn(List.of(other));
    }

    @Test
    @DisplayName("RULE-16: same role, resource, action and condition (keys reordered) is a 409")
    void duplicateRefused() {
        existing("{\"all\": [{\"field\": \"id\", \"value\": {\"lit\": 2}, \"op\": \"eq\"}]}");
        ConflictException e = assertThrows(ConflictException.class, () -> service.create(request(SCOPE)));
        assertEquals("Phạm vi dữ liệu đã tồn tại. Vui lòng kiểm tra lại.", e.getMessage());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("RULE-16: a different condition is not a duplicate, and the create is audited")
    void differentConditionAllowed() {
        existing("{\"all\":[{\"op\":\"eq\",\"field\":\"id\",\"value\":{\"lit\":5}}]}");
        service.create(request(SCOPE));

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(events, atLeastOnce()).publishEvent(captor.capture());
        assertEquals(1, captor.getAllValues().stream()
                .filter(e -> e instanceof AuditEvent a
                        && "row_level_policies".equals(a.entityType()) && "create".equals(a.action()))
                .count());
    }
}
