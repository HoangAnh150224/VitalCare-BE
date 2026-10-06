package com.vn.vitalcare.care.customer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.care.customer.repository.CustomerRepository;
import com.vn.vitalcare.care.customer.service.impl.CustomerServiceImpl;
import com.vn.vitalcare.entity.Customer;
import com.vn.vitalcare.entity.CustomerStatus;
import com.vn.vitalcare.entity.PatientActivationSource;
import com.vn.vitalcare.identity.user.entity.User;
import com.vn.vitalcare.identity.user.entity.UserStatus;
import com.vn.vitalcare.share.exception.ConflictException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Becoming a customer, and the manual path to becoming a patient. */
class CustomerServiceTest {

    private CustomerRepository repository;
    private CustomerService service;

    @BeforeEach
    void setUp() {
        repository = mock(CustomerRepository.class);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        service = new CustomerServiceImpl(repository, Optional::empty, Clock.fixed(Instant.parse("2026-10-05T03:00:00Z"), ZoneOffset.UTC));
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

    private Customer stored(Customer customer) {
        customer.setUser(user());
        when(repository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(customer));
        return customer;
    }

    private static User user() {
        return new User("+84912345678", null, "hash", "Nguyen Van A", UserStatus.ACTIVE);
    }
}
