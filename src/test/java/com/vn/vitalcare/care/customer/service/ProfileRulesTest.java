package com.vn.vitalcare.care.customer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.care.customer.dto.ProfileRequest;
import com.vn.vitalcare.care.customer.repository.CustomerRepository;
import com.vn.vitalcare.care.customer.service.impl.CustomerServiceImpl;
import com.vn.vitalcare.entity.Customer;
import com.vn.vitalcare.identity.rowlevel.service.RowLevelSecurity;
import com.vn.vitalcare.share.exception.ConflictException;
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

/** KAN-75/78/79/81/84: what a profile may hold, and taking a relative off the account. */
class ProfileRulesTest {

    private static final LocalDate BORN = LocalDate.of(2000, 1, 1);

    private final CustomerRepository customers = mock(CustomerRepository.class);
    private final CustomerService service = new CustomerServiceImpl(customers, Optional::empty,
            mock(RowLevelSecurity.class), Clock.fixed(Instant.parse("2026-11-11T03:00:00Z"), ZoneOffset.UTC));

    private final Customer owner = customer(1L, null);
    private final Customer relative = customer(2L, owner);

    private static Customer customer(long id, Customer guardian) {
        Customer customer = new Customer();
        ReflectionTestUtils.setField(customer, "id", id);
        customer.setGuardian(guardian);
        return customer;
    }

    private static ProfileRequest request(String name, String nationalId, LocalDate issuedOn) {
        return new ProfileRequest(name, "0912345678", BORN, "female", "child", nationalId, "cccd", issuedOn,
                null, null, null, "Hà Nội", "Phường Cầu Giấy", "1 Phố Test");
    }

    @Test
    @DisplayName("an identity number another profile holds is refused on create and on edit; one's own is fine")
    void duplicateNationalId() {
        when(customers.existsByNationalIdAndDeletedAtIsNull("030205003365")).thenReturn(true);
        assertEquals("nationalId", assertThrows(FieldValidationException.class,
                () -> service.createDependent(owner, request("Phạm Đức Hiếu", "030205003365", null))).field());

        when(customers.findByIdAndGuardianIdAndDeletedAtIsNull(2L, 1L)).thenReturn(Optional.of(relative));
        when(customers.findByIdAndDeletedAtIsNull(2L)).thenReturn(Optional.of(relative));
        when(customers.existsByNationalIdAndDeletedAtIsNullAndIdNot("030205003365", 2L)).thenReturn(true);
        assertEquals("nationalId", assertThrows(FieldValidationException.class,
                () -> service.updateOwnProfile(owner, 2L, request("Phạm Đức Hiếu", "030205003365", null))).field());
        verify(customers, never()).save(any());

        when(customers.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        service.updateOwnProfile(owner, 2L, request("Phạm Đức Hiếu", "030205009999", null));
    }

    @Test
    @DisplayName("an issue date before the date of birth is refused; clearing it clears it")
    void issueDate() {
        when(customers.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(owner));
        when(customers.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertEquals("nationalIdIssuedOn", assertThrows(FieldValidationException.class,
                () -> service.updateOwnProfile(owner, 1L, request(null, "030205003365", BORN.minusDays(1)))).field());

        assertNotNull(service.updateOwnProfile(owner, 1L, request(null, "030205003365", BORN)).getNationalIdIssuedOn());
        assertNull(service.updateOwnProfile(owner, 1L, request(null, "030205003365", null)).getNationalIdIssuedOn());
    }

    @Test
    @DisplayName("a relative's name takes letters and spaces only, accents included")
    void nameLetters() {
        for (String name : List.of("Nguyễn Thị 2", "Nguyễn@Thị", "1231231da")) {
            assertEquals("fullName", assertThrows(FieldValidationException.class,
                    () -> service.createDependent(owner, request(name, "030205003365", null))).field());
        }
        when(customers.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        assertEquals("Phạm Đức Hiếu",
                service.createDependent(owner, request("Phạm Đức Hiếu", "030205003365", null)).getFullName());
    }

    @Test
    @DisplayName("deleting: never the owner, never mid-visit or wearing a device; otherwise soft")
    void deleteRelative() {
        assertThrows(ConflictException.class, () -> service.deleteDependent(owner, 1L));

        when(customers.findByIdAndGuardianIdAndDeletedAtIsNull(2L, 1L)).thenReturn(Optional.of(relative));
        when(customers.hasOpenVisit(2L)).thenReturn(true);
        assertThrows(ConflictException.class, () -> service.deleteDependent(owner, 2L));
        when(customers.hasOpenVisit(2L)).thenReturn(false);
        when(customers.wearsDevice(2L)).thenReturn(true);
        assertThrows(ConflictException.class, () -> service.deleteDependent(owner, 2L));
        verify(customers, never()).save(any());

        when(customers.wearsDevice(2L)).thenReturn(false);
        when(customers.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        assertNotNull(service.deleteDependent(owner, 2L).getDeletedAt());
    }
}
