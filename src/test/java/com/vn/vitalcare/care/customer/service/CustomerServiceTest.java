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

import com.vn.vitalcare.care.ClinicRowLevel;
import com.vn.vitalcare.care.customer.repository.CustomerRepository;
import com.vn.vitalcare.care.customer.service.impl.CustomerServiceImpl;
import com.vn.vitalcare.entity.Customer;
import com.vn.vitalcare.entity.CustomerStatus;
import com.vn.vitalcare.entity.PatientActivationSource;
import com.vn.vitalcare.identity.user.entity.User;
import com.vn.vitalcare.identity.user.entity.UserStatus;
import com.vn.vitalcare.identity.user.service.UserService;
import com.vn.vitalcare.share.exception.ConflictException;
import com.vn.vitalcare.share.exception.FieldValidationException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Becoming a customer, and the manual path to becoming a patient. */
class CustomerServiceTest {

    private CustomerRepository repository;
    private UserService users;
    private CustomerService service;

    @BeforeEach
    void setUp() {
        repository = mock(CustomerRepository.class);
        users = mock(UserService.class);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        service = new CustomerServiceImpl(repository, Optional::empty, ClinicRowLevel.unrestricted(),
                Clock.fixed(Instant.parse("2026-10-05T03:00:00Z"), ZoneOffset.UTC), users);
    }

    @Test
    @DisplayName("a registered account becomes a neutral customer with a code derived from its id")
    void createNeutral() {
        when(repository.save(any())).thenAnswer(invocation -> {
            Customer customer = invocation.getArgument(0);
            ReflectionTestUtils.setField(customer, "id", 42L);
            return customer;
        });

        Customer created = service.createNeutral(user());

        assertEquals(CustomerStatus.NEUTRAL, created.getStatus());
        assertEquals("KH000042", created.getCustomerCode());
    }

    @Test
    @DisplayName("staff can activate a neutral customer, and the activation is recorded as manual")
    void manualActivation() {
        Customer customer = stored(new Customer());

        service.activateManually(1L);

        assertEquals(CustomerStatus.PATIENT, customer.getStatus());
        assertEquals(PatientActivationSource.MANUAL, customer.getPatientActivationSource());
        assertNotNull(customer.getPatientActivatedAt());
    }

    @Test
    @DisplayName("activating somebody who is already a patient is refused, not repeated")
    void secondActivationIsRefused() {
        Customer customer = stored(new Customer());
        customer.activateAsPatient(PatientActivationSource.CHECK_IN, 3L, null);

        assertThrows(ConflictException.class, () -> service.activateManually(1L));
        assertEquals(PatientActivationSource.CHECK_IN, customer.getPatientActivationSource());
    }

    @Test
    @DisplayName("a walk-in patient gets a profile without an account")
    void createsAccountlessWalkInProfile() {
        LocalDate birthDate = LocalDate.of(1990, 4, 12);
        when(repository.findWalkInDuplicates("+84901234567", "Nguyen Van A", birthDate, "001234567890"))
                .thenReturn(List.of());
        when(repository.save(any())).thenAnswer(invocation -> {
            Customer customer = invocation.getArgument(0);
            ReflectionTestUtils.setField(customer, "id", 42L);
            return customer;
        });

        Customer created = service.createWalkInProfile(" Nguyen Van A ", "0901234567", birthDate, "F", "001234567890");

        assertNull(created.getUser());
        assertNull(created.getGuardian());
        assertEquals("Nguyen Van A", created.getFullName());
        assertEquals("+84901234567", created.getPhone());
        assertEquals("001234567890", created.getNationalId());
        assertEquals(CustomerStatus.NEUTRAL, created.getStatus());
        assertEquals("KH000042", created.getCustomerCode());
    }

    @Test
    @DisplayName("a walk-in duplicate is refused before a second profile is saved")
    void duplicateWalkInIsRefused() {
        LocalDate birthDate = LocalDate.of(1990, 4, 12);
        when(repository.findWalkInDuplicates("+84901234567", "Nguyen Van A", birthDate, null))
                .thenReturn(List.of(new Customer()));

        assertThrows(ConflictException.class,
                () -> service.createWalkInProfile("Nguyen Van A", "0901234567", birthDate, "F", null));

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("an account links only to a walk-in profile matching phone, name and birth date")
    void linksAccountToMatchingWalkIn() {
        LocalDate birthDate = LocalDate.of(1990, 4, 12);
        User account = user();
        Customer walkIn = new Customer();
        walkIn.setFullName("Nguyen Van A");
        walkIn.setPhone("+84912345678");
        walkIn.setDateOfBirth(birthDate);
        when(repository.findUnclaimedWalkInsByPhoneAndName(account.getPhone(), account.getFullName()))
                .thenReturn(List.of(walkIn));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Customer linked = service.registerAccountProfile(account, birthDate);

        assertEquals(account, linked.getUser());
        assertNull(ReflectionTestUtils.getField(linked, "fullName"));
        assertNull(ReflectionTestUtils.getField(linked, "phone"));
        assertEquals(account.getFullName(), linked.getFullName());
        assertEquals(account.getPhone(), linked.getPhone());
        assertEquals(birthDate, linked.getDateOfBirth());
        verify(users).synchronizeCustomerRole(account, false);
    }

    @Test
    @DisplayName("linking a walk-in requires birth date when phone and name match")
    void walkInLinkRequiresBirthDate() {
        User account = user();
        Customer walkIn = new Customer();
        when(repository.findUnclaimedWalkInsByPhoneAndName(account.getPhone(), account.getFullName()))
                .thenReturn(List.of(walkIn));

        assertThrows(FieldValidationException.class, () -> service.registerAccountProfile(account, null));

        assertNull(walkIn.getUser());
        verify(repository, never()).save(any());
        verify(users, never()).synchronizeCustomerRole(any(), any(Boolean.class));
    }

    private Customer stored(Customer customer) {
        customer.setUser(user());
        when(repository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(customer));
        return customer;
    }

    private static User user() {
        return new User("+84912345678", null, "hash", "Nguyen Van A", UserStatus.ACTIVE);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("SRS 3.8.5: leaving 'active' needs a reason; the change says when, and the same status twice is refused")
    void activityStatus() {
        Customer customer = new Customer();
        when(repository.findByIdAndDeletedAtIsNull(5L)).thenReturn(Optional.of(customer));

        org.junit.jupiter.api.Assertions.assertThrows(com.vn.vitalcare.share.exception.FieldValidationException.class,
                () -> service.changeActivityStatus(5L, new com.vn.vitalcare.care.customer.dto.ActivityStatusRequest("paused", " ")));
        service.changeActivityStatus(5L, new com.vn.vitalcare.care.customer.dto.ActivityStatusRequest("paused", "Đi công tác 2 tuần"));
        org.junit.jupiter.api.Assertions.assertEquals("paused", customer.getActivityStatus());
        org.junit.jupiter.api.Assertions.assertEquals("Đi công tác 2 tuần", customer.getActivityReason());
        org.junit.jupiter.api.Assertions.assertNotNull(customer.getActivityChangedAt());
        org.junit.jupiter.api.Assertions.assertThrows(com.vn.vitalcare.share.exception.ConflictException.class,
                () -> service.changeActivityStatus(5L, new com.vn.vitalcare.care.customer.dto.ActivityStatusRequest("paused", "x")));
        service.changeActivityStatus(5L, new com.vn.vitalcare.care.customer.dto.ActivityStatusRequest("active", null));
        org.junit.jupiter.api.Assertions.assertEquals("active", customer.getActivityStatus());
    }
}
