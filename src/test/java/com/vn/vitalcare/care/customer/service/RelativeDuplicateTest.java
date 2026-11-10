package com.vn.vitalcare.care.customer.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.care.customer.dto.ProfileRequest;
import com.vn.vitalcare.care.customer.repository.CustomerRepository;
import com.vn.vitalcare.care.customer.service.impl.CustomerServiceImpl;
import com.vn.vitalcare.entity.Customer;
import com.vn.vitalcare.identity.rowlevel.service.RowLevelSecurity;
import com.vn.vitalcare.share.exception.FieldValidationException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** One person is one relative under an account, whether added twice or renamed into another. */
class RelativeDuplicateTest {

    private final CustomerRepository customers = mock(CustomerRepository.class);
    private final CustomerService service = new CustomerServiceImpl(customers, Optional::empty,
            mock(RowLevelSecurity.class), Clock.fixed(Instant.parse("2026-11-11T03:00:00Z"), ZoneOffset.UTC));

    private static Customer relative(long id, Customer owner, String name, LocalDate born) {
        Customer relative = new Customer();
        ReflectionTestUtils.setField(relative, "id", id);
        relative.setGuardian(owner);
        relative.setFullName(name);
        relative.setDateOfBirth(born);
        return relative;
    }

    private static ProfileRequest request(String name, LocalDate born) {
        return new ProfileRequest(name, "0912345678", born, "female", "parent", "001182000123", "cccd", null,
                null, null, null, "Hà Nội", "Phường Cầu Giấy", "1 Phố Test");
    }

    @Test
    @DisplayName("renaming the second relative into the first is refused on the name and nothing is saved; keeping its own details is fine")
    void renamedIntoAnother() {
        Customer owner = new Customer();
        ReflectionTestUtils.setField(owner, "id", 1L);
        LocalDate born = LocalDate.of(1982, 1, 1);
        Customer first = relative(2L, owner, "Trần Thị Phương", born);
        Customer second = relative(3L, owner, "Nguyễn Văn Bình", LocalDate.of(1990, 5, 5));
        when(customers.findByGuardianIdAndDeletedAtIsNullOrderByIdAsc(1L)).thenReturn(List.of(first, second));
        when(customers.findByIdAndGuardianIdAndDeletedAtIsNull(eq(3L), eq(1L))).thenReturn(Optional.of(second));
        when(customers.findByIdAndDeletedAtIsNull(3L)).thenReturn(Optional.of(second));
        when(customers.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        FieldValidationException refused = assertThrows(FieldValidationException.class,
                () -> service.updateOwnProfile(owner, 3L, request("  trần   thị PHƯƠNG ", born)));
        assertEquals("fullName", refused.field());
        verify(customers, never()).save(any());

        Customer again = relative(3L, owner, "Nguyễn Văn Bình", LocalDate.of(1990, 5, 5));
        when(customers.findByIdAndDeletedAtIsNull(3L)).thenReturn(Optional.of(again));
        assertDoesNotThrow(() -> service.updateOwnProfile(owner, 3L, request("Nguyễn Văn Bình", LocalDate.of(1990, 5, 5))));
    }

    @Test
    @DisplayName("adding the same relative twice is refused on the name too")
    void addedTwice() {
        Customer owner = new Customer();
        ReflectionTestUtils.setField(owner, "id", 1L);
        LocalDate born = LocalDate.of(1982, 1, 1);
        when(customers.findByGuardianIdAndDeletedAtIsNullOrderByIdAsc(1L))
                .thenReturn(List.of(relative(2L, owner, "Trần Thị Phương", born)));
        assertEquals("fullName", assertThrows(FieldValidationException.class,
                () -> service.createDependent(owner, request("Trần Thị Phương", born))).field());
    }
}
