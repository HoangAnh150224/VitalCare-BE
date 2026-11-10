package com.vn.vitalcare.care.customer.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.care.customer.dto.ProfileRequest;
import com.vn.vitalcare.care.customer.repository.CustomerRepository;
import com.vn.vitalcare.care.customer.service.impl.CustomerServiceImpl;
import com.vn.vitalcare.entity.Customer;
import com.vn.vitalcare.share.exception.FieldValidationException;
import com.vn.vitalcare.identity.rowlevel.service.RowLevelSecurity;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A date of birth more than 120 years back is a typing slip, refused on the field. */
class BirthDateLimitTest {

    private final CustomerRepository customers = mock(CustomerRepository.class);
    private final CustomerService service = new CustomerServiceImpl(customers, Optional::empty,
            mock(RowLevelSecurity.class), Clock.fixed(Instant.parse("2026-11-11T03:00:00Z"), ZoneOffset.UTC));

    private ProfileRequest relative(LocalDate born) {
        return new ProfileRequest("Nguyễn Văn A", "0912345678", born, "male", "child", "001218009873", "cccd", null,
                null, null, null, "Hà Nội", "Phường Cầu Giấy", "1 Phố Test");
    }

    @Test
    @DisplayName("a relative born in year 1 is refused on the date of birth; 120 years ago to the day is taken")
    void ageLimit() {
        when(customers.findByGuardianIdAndDeletedAtIsNullOrderByIdAsc(any())).thenReturn(List.of());
        when(customers.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        Customer owner = new Customer();

        FieldValidationException refused = assertThrows(FieldValidationException.class,
                () -> service.createDependent(owner, relative(LocalDate.of(1, 1, 1))));
        assertEquals("dateOfBirth", refused.field());
        assertThrows(FieldValidationException.class,
                () -> service.createDependent(owner, relative(LocalDate.of(1906, 11, 10))));
        assertDoesNotThrow(() -> service.createDependent(owner, relative(LocalDate.of(1906, 11, 11))));
    }
}
