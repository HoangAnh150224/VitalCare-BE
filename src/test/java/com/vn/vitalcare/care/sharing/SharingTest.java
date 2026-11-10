package com.vn.vitalcare.care.sharing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.care.customer.service.CustomerService;
import com.vn.vitalcare.entity.Customer;
import com.vn.vitalcare.entity.SharingPermission;
import com.vn.vitalcare.identity.user.entity.User;
import com.vn.vitalcare.identity.user.service.UserService;
import com.vn.vitalcare.share.exception.ConflictException;
import com.vn.vitalcare.share.exception.FieldValidationException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;

/** BF-06 Sharing Permission: who may be a caregiver, how many, and what a grant ends with. */
class SharingTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 11, 3, 9, 0, 0, 0, ZoneOffset.ofHours(7));

    private SharingPermissionRepository permissions;
    private UserService users;
    private Sharing sharing;
    private Customer me;
    private final List<SharingPermission> stored = new ArrayList<>();

    @BeforeEach
    void setUp() {
        permissions = mock(SharingPermissionRepository.class);
        CustomerService customers = mock(CustomerService.class);
        users = mock(UserService.class);
        sharing = new Sharing(permissions, customers, users, mock(ApplicationEventPublisher.class),
                Clock.fixed(Instant.from(NOW), ZoneOffset.UTC));
        me = new Customer();
        ReflectionTestUtils.setField(me, "id", 37L);
        when(customers.getForCurrentUser()).thenReturn(me);
        when(customers.profilesOf(me)).thenReturn(List.of(me));
        when(permissions.save(any(SharingPermission.class))).thenAnswer(call -> {
            SharingPermission p = call.getArgument(0);
            if (p.getId() == null) {
                ReflectionTestUtils.setField(p, "id", (long) stored.size() + 1);
                stored.add(p);
            }
            return p;
        });
        when(permissions.findInForceOf(eq(37L), any())).thenAnswer(call ->
                stored.stream().filter(p -> p.inForce(call.getArgument(1))).toList());
        when(permissions.findWithParties(anyLong())).thenAnswer(call ->
                stored.stream().filter(p -> p.getId().equals(call.getArgument(0))).findFirst());
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                Jwt.withTokenValue("t").header("alg", "none").subject("58").claim("uid", 58L).build()));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private User account(long id, String phone) {
        User u = mock(User.class);
        when(u.getId()).thenReturn(id);
        when(u.getFullName()).thenReturn("Người " + id);
        when(u.canSignIn()).thenReturn(true);
        when(u.getDeletedAt()).thenReturn(null);
        when(u.roleCodes()).thenReturn(Set.of("GUEST"));
        when(users.findForAuthentication(phone)).thenReturn(Optional.of(u));
        return u;
    }

    private static SharingDtos.GrantRequest grant(String phone) {
        return new SharingDtos.GrantRequest(null, phone, true, true, false, "0912345678", null, null);
    }

    @Test
    @DisplayName("Two caregivers in force; a third is refused until one is revoked")
    void atMostTwo() {
        account(101, "0900000101");
        account(102, "0900000102");
        account(103, "0900000103");
        sharing.grant(grant("0900000101"));
        SharingPermission second = sharing.grant(grant("0900000102"));

        assertThatThrownBy(() -> sharing.grant(grant("0900000103")))
                .isInstanceOf(ConflictException.class).hasMessageContaining("two caregivers");

        sharing.revoke(second.getId());
        assertThat(sharing.grant(grant("0900000103")).getStatus()).isEqualTo(SharingPermission.ACTIVE);
    }

    @Test
    @DisplayName("Only somebody with an account, not oneself, not twice; an emergency number is required")
    void whoMayBeACaregiver() {
        account(101, "0900000101");
        when(users.findForAuthentication("0900000999")).thenReturn(Optional.empty());
        account(58, "0900000501");

        assertThatThrownBy(() -> sharing.grant(grant("0900000999"))).isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> sharing.grant(grant("0900000501"))).isInstanceOf(FieldValidationException.class);
        sharing.grant(grant("0900000101"));
        assertThatThrownBy(() -> sharing.grant(grant("0900000101")))
                .isInstanceOf(ConflictException.class).hasMessageContaining("already has access");
        assertThatThrownBy(() -> sharing.grant(new SharingDtos.GrantRequest(null, "0900000101", true, true, false,
                "not a phone", null, null))).isInstanceOf(FieldValidationException.class);
    }

    @Test
    @DisplayName("An expired grant no longer counts, and cannot be changed; a revoked one cannot be revoked again")
    void expiryAndRevocation() {
        account(101, "0900000101");
        SharingPermission p = sharing.grant(grant("0900000101"));
        p.setExpiresAt(NOW.minusMinutes(1));

        assertThat(p.inForce(NOW)).isFalse();
        assertThatThrownBy(() -> sharing.update(p.getId(), new SharingDtos.UpdateRequest(false, null, null, null, null, null, null)))
                .isInstanceOf(ConflictException.class);
        sharing.revoke(p.getId());
        assertThatThrownBy(() -> sharing.revoke(p.getId())).isInstanceOf(ConflictException.class);
    }
}
