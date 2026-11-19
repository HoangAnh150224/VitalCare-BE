package com.vn.vitalcare.care.iomt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.care.assignment.repository.DeviceAssignmentRepository;
import com.vn.vitalcare.care.customer.service.CustomerService;
import com.vn.vitalcare.care.device.repository.MedicalDeviceRepository;
import com.vn.vitalcare.entity.Customer;
import com.vn.vitalcare.entity.DeviceAssignment;
import com.vn.vitalcare.entity.MedicalDevice;
import com.vn.vitalcare.share.exception.ConflictException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class MyDevicesTest {

    private static final long ME = 7L;
    private static final OffsetDateTime FIRST = OffsetDateTime.parse("2026-11-01T08:00:00Z");
    private static final Instant NOW = Instant.parse("2026-11-19T09:00:00Z");

    private MedicalDevice device;
    private MyDevices myDevices;

    @BeforeEach
    void setUp() {
        device = new MedicalDevice();
        device.setDeviceCode("VC-DEMO-BN01");
        DeviceAssignment worn = new DeviceAssignment();
        worn.setDevice(device);

        Customer owner = mock(Customer.class);
        when(owner.getId()).thenReturn(1L);
        CustomerService customers = mock(CustomerService.class);
        when(customers.getForCurrentUser()).thenReturn(owner);
        when(customers.profilesOf(owner)).thenReturn(List.of(owner));
        DeviceAssignmentRepository assignments = mock(DeviceAssignmentRepository.class);
        when(assignments.findWornByCustomerIdInAndUnassignedAtIsNullAndDeletedAtIsNull(anyCollection()))
                .thenReturn(List.of(worn));
        MedicalDeviceRepository devices = mock(MedicalDeviceRepository.class);
        when(devices.findForUpdateByCode("VC-DEMO-BN01")).thenReturn(Optional.of(device));

        myDevices = new MyDevices(customers, assignments, devices, mock(VitalIngestion.class),
                Clock.fixed(NOW, ZoneOffset.UTC));
        Jwt token = Jwt.withTokenValue("test").header("alg", "none").subject(String.valueOf(ME))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(token));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("an unpaired device pairs with the caller, stamped now")
    void pairs() {
        myDevices.pair("vc-demo-bn01");

        assertEquals(ME, device.getPairedByUserId());
        assertEquals(NOW.atOffset(ZoneOffset.UTC), device.getPairedAt());
    }

    @Test
    @DisplayName("pairing a device already paired with the caller is refused and keeps the first pairedAt (KAN-95)")
    void alreadyMine() {
        device.pair(ME, FIRST);

        ConflictException e = assertThrows(ConflictException.class, () -> myDevices.pair("VC-DEMO-BN01"));

        assertEquals("Thiết bị này đã được ghép với tài khoản của bạn.", e.getMessage());
        assertEquals(FIRST, device.getPairedAt());
    }

    @Test
    @DisplayName("a device paired with another phone is still refused")
    void someoneElses() {
        device.pair(99L, FIRST);

        assertThrows(ConflictException.class, () -> myDevices.pair("VC-DEMO-BN01"));
        assertEquals(99L, device.getPairedByUserId());
    }
}
