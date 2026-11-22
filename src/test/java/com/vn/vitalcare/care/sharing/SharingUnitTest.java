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

/** FE-11 Caregiver Access + FE-21 Patient Health Data Sharing & Permission. Report-5.1, sheet BE_Sharing. */
class SharingUnitTest {

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

    private static SharingDtos.UpdateRequest change(Boolean vitals, Boolean alerts, Boolean appts, String phone,
                                                   OffsetDateTime expires, Boolean clear, String note) {
        return new SharingDtos.UpdateRequest(vitals, alerts, appts, phone, expires, clear, note);
    }

    @Test
    void sh01_grantDefaults_vitalsAndAlertsShared_appointmentsNot_phoneE164_noteTrimmed() {
        account(101, "0900000101");
        SharingPermission p = sharing.grant(new SharingDtos.GrantRequest(null, "0900000101", null, null, null,
                "0912345678", NOW.plusDays(30), "  Con gái  "));
        assertThat(p.getStatus()).isEqualTo(SharingPermission.ACTIVE);
        assertThat(p.isShareVitals()).isTrue();
        assertThat(p.isShareAlerts()).isTrue();
        assertThat(p.isShareAppointments()).isFalse();
        assertThat(p.getEmergencyPhone()).isEqualTo("+84912345678");
        assertThat(p.getNote()).isEqualTo("Con gái");
        assertThat(p.getCustomer()).isSameAs(me);
    }

    @Test
    void sh02_endOfAccessMustBeInTheFuture() {
        account(101, "0900000101");
        assertThatThrownBy(() -> sharing.grant(new SharingDtos.GrantRequest(null, "0900000101", true, true, false,
                "0912345678", NOW, null)))
                .isInstanceOf(FieldValidationException.class).hasMessage("The end of access must be in the future");
    }

    @Test
    void sh03_staffAccountCannotBeACaregiver() {
        User doctor = account(120, "0900000120");
        when(doctor.roleCodes()).thenReturn(Set.of("DOCTOR"));
        assertThatThrownBy(() -> sharing.grant(grant("0900000120")))
                .isInstanceOf(FieldValidationException.class)
                .hasMessage("No active Guest or Patient account uses that number; ask the caregiver to register first");
    }

    @Test
    void sh04_lockedAccountCannotBeACaregiver() {
        User locked = account(121, "0900000121");
        when(locked.canSignIn()).thenReturn(false);
        assertThatThrownBy(() -> sharing.grant(grant("0900000121"))).isInstanceOf(FieldValidationException.class);
    }

    @Test
    void sh05_updateScope_clearExpiry_blankNoteClears() {
        account(101, "0900000101");
        SharingPermission p = sharing.grant(new SharingDtos.GrantRequest(null, "0900000101", true, true, false,
                "0912345678", NOW.plusDays(3), "Ghi chú"));
        sharing.update(p.getId(), change(false, null, true, "+84987654321", null, true, " "));
        assertThat(p.isShareVitals()).isFalse();
        assertThat(p.isShareAlerts()).isTrue();
        assertThat(p.isShareAppointments()).isTrue();
        assertThat(p.getEmergencyPhone()).isEqualTo("+84987654321");
        assertThat(p.getExpiresAt()).isNull();
        assertThat(p.getNote()).isNull();
    }

    @Test
    void sh06_updateRefusesBadPhoneAndPastEnd() {
        account(101, "0900000101");
        SharingPermission p = sharing.grant(grant("0900000101"));
        assertThatThrownBy(() -> sharing.update(p.getId(), change(null, null, null, "12ab", null, null, null)))
                .isInstanceOf(FieldValidationException.class).hasMessage("Enter a Vietnamese phone number");
        assertThatThrownBy(() -> sharing.update(p.getId(), change(null, null, null, null, NOW.minusDays(1), null, null)))
                .isInstanceOf(FieldValidationException.class).hasMessage("The end of access must be in the future");
    }

    @Test
    void sh07_revokeEndsAccessAtOnce() {
        account(101, "0900000101");
        SharingPermission p = sharing.grant(grant("0900000101"));
        sharing.revoke(p.getId());
        assertThat(p.getStatus()).isEqualTo(SharingPermission.REVOKED);
        assertThat(p.getRevokedAt()).isEqualTo(NOW.withOffsetSameInstant(ZoneOffset.UTC));
        assertThat(p.getRevokedById()).isEqualTo(58L);
        assertThat(p.inForce(NOW)).isFalse();
    }

    @Test
    void sh08_someoneElsesGrantIsNotFound() {
        assertThatThrownBy(() -> sharing.revoke(999L))
                .isInstanceOf(com.vn.vitalcare.share.exception.ResourceNotFoundException.class);
    }

    @Test
    void sh09_caregiverSeesOnlyTheCoveredPart() {
        account(101, "0900000101");
        SharingPermission p = sharing.grant(grant("0900000101"));
        SharedPatients shared = new SharedPatients(permissions, Clock.fixed(Instant.from(NOW), ZoneOffset.UTC));
        when(permissions.findInForceFor(eq(58L), any())).thenReturn(List.of(p));
        assertThat(shared.require(37L, SharingPermission::isShareVitals)).isSameAs(p);
        assertThatThrownBy(() -> shared.require(37L, SharingPermission::isShareAppointments))
                .isInstanceOf(com.vn.vitalcare.share.exception.ResourceNotFoundException.class);
        assertThatThrownBy(() -> shared.require(38L, SharingPermission::isShareVitals))
                .isInstanceOf(com.vn.vitalcare.share.exception.ResourceNotFoundException.class);
    }
}
