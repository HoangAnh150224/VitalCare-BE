package com.vn.vitalcare.care.iomt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

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

/** FE-02 Device Pairing / FE-06 upload from the phone: MyDevices.unpair, upload, wornByCode. Report-5.1, sheet BE_Gaps. */
class MyDevicesUnitTest {

    private static final long ME = 7L;
    private static final OffsetDateTime FIRST = OffsetDateTime.parse("2026-11-01T08:00:00Z");
    private static final Instant NOW = Instant.parse("2026-11-19T09:00:00Z");

    private MedicalDevice device;
    private MyDevices myDevices;
    private VitalIngestion ingestion;
    private DeviceAssignment worn;
    private MedicalDeviceRepository devicesRepo;

    @BeforeEach
    void setUp() {
        device = new MedicalDevice();
        device.setDeviceCode("VC-DEMO-BN01");
        worn = new DeviceAssignment();
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
        devicesRepo = devices;
        when(devices.findForUpdateByCode("VC-DEMO-BN01")).thenReturn(Optional.of(device));

        ingestion = mock(VitalIngestion.class);
        myDevices = new MyDevices(customers, assignments, devices, ingestion,
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
    void mdv01_unpairClearsThePairing() {
        device.pair(ME, FIRST);
        myDevices.unpair(" vc-demo-bn01 ");
        assertNull(device.getPairedByUserId());
        assertNull(device.getPairedAt());
    }

    @Test
    void mdv02_aDeviceNotOnMyProfilesIsNotFound_beforeAnythingIsRead() {
        when(devicesRepo.findForUpdateByCode("VC-OTHER")).thenReturn(Optional.empty());
        assertThrows(com.vn.vitalcare.share.exception.ResourceNotFoundException.class, () -> myDevices.unpair("vc-other"));
        assertThrows(com.vn.vitalcare.share.exception.ResourceNotFoundException.class,
                () -> myDevices.upload("vc-other", new DeviceUpload(null, null, null, null, null, List.of())));
        verifyNoInteractions(ingestion);
    }

    @Test
    void mdv03_aDeviceThatExistsButIsWornBySomeoneElseIsNotFoundEither() {
        MedicalDevice other = new MedicalDevice();
        other.setDeviceCode("VC-OTHER");
        when(devicesRepo.findForUpdateByCode("VC-OTHER")).thenReturn(Optional.of(other));
        assertThrows(com.vn.vitalcare.share.exception.ResourceNotFoundException.class, () -> myDevices.wornByCode("VC-OTHER"));
    }

    @Test
    void mdv04_uploadGoesOnlyIntoMyOwnTimeWithTheDevice() {
        DeviceUpload upload = new DeviceUpload("b1", 0, 80, "1.4.2", null, List.of());
        SyncResult result = new SyncResult(1L, "SUCCESS", 0, 0, 0, 0, List.of());
        when(ingestion.ingest(eq("VC-DEMO-BN01"), same(upload), eq("REST"), same(worn))).thenReturn(result);
        assertSame(result, myDevices.upload("vc-demo-bn01", upload));
        verify(ingestion).ingest(eq("VC-DEMO-BN01"), same(upload), eq("REST"), same(worn));
    }

    @Test
    void mdv05_wornListsTheDevicesOnEveryProfileOfTheAccount() {
        assertEquals(List.of(worn), myDevices.worn());
    }
}
