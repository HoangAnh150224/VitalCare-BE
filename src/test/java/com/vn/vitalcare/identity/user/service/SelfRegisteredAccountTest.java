package com.vn.vitalcare.identity.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.identity.role.entity.Role;
import com.vn.vitalcare.identity.role.service.RoleService;
import com.vn.vitalcare.identity.token.service.RefreshTokenService;
import com.vn.vitalcare.identity.user.entity.User;
import com.vn.vitalcare.identity.user.repository.UserRepository;
import com.vn.vitalcare.share.exception.ConflictException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;

/** A self-registered account keeps its email — the way back in — and may not take another account's. */
class SelfRegisteredAccountTest {

    private UserRepository repository;
    private UserService service;

    @BeforeEach
    void setUp() {
        repository = mock(UserRepository.class);
        when(repository.findByPhone(any())).thenReturn(Optional.empty());
        when(repository.findByEmailIgnoreCase(any())).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        RoleService roles = mock(RoleService.class);
        when(roles.getByCode("CUSTOMER")).thenReturn(mock(Role.class));
        service = new UserService(repository, roles, mock(RefreshTokenService.class),
                mock(PasswordEncoder.class), mock(ApplicationEventPublisher.class));
    }

    @Test
    @DisplayName("the email is stored trimmed and lower-cased, the number in +84 form")
    void storesEmail() {
        User user = service.createSelfRegistered("0912345678", "  An@Example.COM ", "Passw0rd!", "An", "CUSTOMER");

        assertEquals("an@example.com", user.getEmail());
        assertEquals("+84912345678", user.getPhone());
    }

    @Test
    @DisplayName("no email is fine: the account is created without one")
    void emailIsOptional() {
        User user = service.createSelfRegistered("0912345678", null, "Passw0rd!", "An", "CUSTOMER");

        assertEquals(null, user.getEmail());
        verify(repository, never()).findByEmailIgnoreCase(any());
    }

    @Test
    @DisplayName("an email another account holds is refused, nothing saved")
    void takenEmailIsRefused() {
        User other = mock(User.class);
        when(other.getId()).thenReturn(7L);
        when(repository.findByEmailIgnoreCase("an@example.com")).thenReturn(Optional.of(other));

        assertThrows(ConflictException.class,
                () -> service.createSelfRegistered("0912345678", "an@example.com", "Passw0rd!", "An", "CUSTOMER"));
        verify(repository, never()).save(any());
    }
}
