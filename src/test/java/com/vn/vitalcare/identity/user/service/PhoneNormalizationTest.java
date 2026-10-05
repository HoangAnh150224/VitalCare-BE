package com.vn.vitalcare.identity.user.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.identity.role.service.RoleService;
import com.vn.vitalcare.identity.token.service.RefreshTokenService;
import com.vn.vitalcare.identity.user.dto.UserPatchRequest;
import com.vn.vitalcare.identity.user.entity.User;
import com.vn.vitalcare.identity.user.entity.UserStatus;
import com.vn.vitalcare.identity.user.repository.UserRepository;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The one thing that makes {@code uq_users_phone} mean "one account per
 * number".
 *
 * <p>Postgres compares strings, not phone numbers: {@code 0901234567} and
 * {@code +84901234567} satisfy a unique constraint together quite happily. The
 * constraint therefore only enforces what it exists to enforce if every write
 * and every lookup arrives spelled one way, which makes that conversion the
 * single point where the whole scheme can be wrong.
 *
 * <p>Asserted through the public methods rather than on the private helper, so
 * that what is checked is the value the repository actually receives.
 */
class PhoneNormalizationTest {

    private UserRepository repository;
    private RefreshTokenService refreshTokens;
    private UserService service;

    @BeforeEach
    void setUp() {
        repository = mock(UserRepository.class);
        refreshTokens = mock(RefreshTokenService.class);
        // The repository and the token service take part in the paths under
        // test; the rest are constructor dependencies never reached from here.
        service = new UserService(
                repository,
                mock(RoleService.class),
                refreshTokens,
                mock(PasswordEncoder.class),
                mock(ApplicationEventPublisher.class));
    }

    @Test
    @DisplayName("every accepted spelling reaches the repository as +84 and nine digits")
    void spellingsCollapseToOneForm() {
        assertLooksUp("0901234567", "+84901234567");
        assertLooksUp("+84901234567", "+84901234567");
        assertLooksUp("84901234567", "+84901234567");

        // Whatever people put between the digits.
        assertLooksUp("090 123 4567", "+84901234567");
        assertLooksUp("090-123-4567", "+84901234567");
        assertLooksUp("090.123.4567", "+84901234567");
        assertLooksUp("(090) 123 4567", "+84901234567");
        assertLooksUp("  +84 901 234 567  ", "+84901234567");

        // Pasted from a web page or a chat app: no-break spaces, which Java's
        // \s does not cover.
        assertLooksUp("090 123 4567", "+84901234567");
        assertLooksUp("+84 901 234 567 ", "+84901234567");
    }

    /**
     * The DTO pattern is the first gate and must accept everything the service
     * normalises from a paste, or a number that signs in cannot be enrolled.
     */
    @Test
    @DisplayName("the request pattern accepts no-break spaces too")
    void requestPatternAcceptsNoBreakSpaces() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            assertTrue(validator.validate(patchPhone("090 123 4567")).isEmpty());
            assertTrue(validator.validate(patchPhone("+84 901 234 567")).isEmpty());
            assertFalse(validator.validate(patchPhone("090 123")).isEmpty());
        }
    }

    @Test
    @DisplayName("moving the sign-in number ends every open session")
    void phoneChangeRevokesSessions() {
        User user = existingUser();
        when(repository.findById(1L)).thenReturn(Optional.of(user));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.update(1L, patchPhone("0901234567"));

        verify(refreshTokens).revokeAllFor(eq(user), any());
    }

    @Test
    @DisplayName("re-sending the same number in another spelling keeps sessions open")
    void samePhoneDoesNotRevokeSessions() {
        when(repository.findById(1L)).thenReturn(Optional.of(existingUser()));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // existingUser() holds +84900000009.
        service.update(1L, patchPhone("090 000 0009"));

        verify(refreshTokens, never()).revokeAllFor(any(), any());
    }

    /**
     * Two spellings of one number produce one lookup key, which is the property
     * the uniqueness check rests on: it cannot be walked around by typing the
     * same number a different way.
     */
    @Test
    @DisplayName("a second spelling collides with the stored form, not beside it")
    void normalisationIsWhatMakesTheConstraintWork() {
        assertLooksUp("0987654321", "+84987654321");
        assertLooksUp("+84987654321", "+84987654321");
    }

    @Test
    @DisplayName("a number that cannot be read resolves to empty without a query")
    void malformedNumbersAreNotLookedUp() {
        // Signing in must not be able to tell "malformed" from "no such
        // account": both answer the same way, and neither reaches the database.
        assertRejected(null);
        assertRejected("");
        assertRejected("    ");
        assertRejected("---");
        assertRejected("abc");
        assertRejected("0901234");          // too short
        assertRejected("09012345678");      // too long
        assertRejected("+840901234567");    // country code and trunk zero both
        assertRejected("840901234567");     // the same, without the plus
        assertRejected("+1202555019");      // not Vietnam
    }

    @Test
    @DisplayName("structurally valid but unissued prefixes are accepted on purpose")
    void prefixesAreNotPoliced() {
        // Carrier prefixes are deliberately not enumerated -- a list of them
        // needs editing every time one is allocated. A number like this is a
        // wrong number, which is what a failed sign-in already says.
        assertLooksUp("0000000000", "+84000000000");
        assertLooksUp("0111111111", "+84111111111");
    }

    @Test
    @DisplayName("a write refuses a number a lookup would merely fail to find")
    void writesRefuseAMalformedNumber() {
        when(repository.findById(1L)).thenReturn(Optional.of(existingUser()));

        // The DTO pattern catches this first in a real request; this is the
        // second line, and it must not write something unnormalised instead.
        assertThrows(
                IllegalArgumentException.class,
                () -> service.update(1L, patchPhone("not-a-number")));

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("a write normalises before it checks the number is free")
    void writesCheckTheNormalisedForm() {
        when(repository.findById(1L)).thenReturn(Optional.of(existingUser()));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // findByPhone returns empty, so the number reads as available and the
        // update goes through; what matters is the spelling it was checked as.
        service.update(1L, patchPhone("090-123 4567"));

        verify(repository).findByPhone("+84901234567");
    }

    private void assertLooksUp(String typed, String expected) {
        service.findForAuthentication(typed);
        verify(repository).findByPhone(expected);
        // A clean record of calls per spelling, so one assertion cannot be
        // satisfied by an earlier spelling's lookup.
        setUp();
    }

    private void assertRejected(String typed) {
        assertTrue(service.findForAuthentication(typed).isEmpty(), "accepted: " + typed);
        verify(repository, never()).findByPhone(any());
        setUp();
    }

    private static User existingUser() {
        return new User("+84900000009", null, "irrelevant", "Existing Account", UserStatus.ACTIVE);
    }

    private static UserPatchRequest patchPhone(String phone) {
        return new UserPatchRequest(phone, null, null, null, null);
    }
}
