package com.vn.vitalcare.care.iomt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.care.appointment.service.AppointmentService;
import com.vn.vitalcare.care.appointment.service.NoShowSweep;
import com.vn.vitalcare.care.device.repository.MedicalDeviceRepository;
import com.vn.vitalcare.care.device.service.DeviceService;
import com.vn.vitalcare.entity.MedicalDevice;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** FE-02 Device Pairing: the device's MQTT token (DeviceTokens); FE-10 the no-show job (NoShowSweep). Report-5.1, sheet BE_Gaps. */
class DeviceTokensUnitTest {

    private MedicalDeviceRepository devices;
    private DeviceTokens tokens;
    private MedicalDevice band;

    @BeforeEach
    void setUp() {
        devices = mock(MedicalDeviceRepository.class);
        DeviceService deviceService = mock(DeviceService.class);
        tokens = new DeviceTokens(devices, deviceService);
        band = new MedicalDevice();
        band.setDeviceCode("VC-BAND-01");
        when(deviceService.lockForUpdate(3L)).thenReturn(band);
        when(devices.findByCode("VC-BAND-01")).thenReturn(Optional.of(band));
    }

    @Test void tk01_issuedTokenIsKeptOnlyAsItsHash_andVerifies() {
        DeviceTokens.Issued issued = tokens.issue(3L);
        assertEquals("VC-BAND-01", issued.deviceCode());
        assertEquals(32, issued.token().length());
        assertEquals(DeviceTokens.hash(issued.token()), band.getTokenHash());
        assertNotEquals(issued.token(), band.getTokenHash());
        verify(devices).persist(band);
        assertTrue(tokens.verify(" vc-band-01 ", " " + issued.token() + " "));
    }

    @Test void tk02_reissuingReplacesTheOldToken() {
        String first = tokens.issue(3L).token();
        String second = tokens.issue(3L).token();
        assertNotEquals(first, second);
        assertFalse(tokens.verify("VC-BAND-01", first));
        assertTrue(tokens.verify("VC-BAND-01", second));
    }

    @Test void tk03_wrongBlankOrMissingRefused_unknownDeviceRefused() {
        tokens.issue(3L);
        assertFalse(tokens.verify("VC-BAND-01", "wrong"));
        assertFalse(tokens.verify("VC-BAND-01", " "));
        assertFalse(tokens.verify(null, "x"));
        assertFalse(tokens.verify("VC-BAND-01", null));
        when(devices.findByCode("VC-NONE")).thenReturn(Optional.empty());
        assertFalse(tokens.verify("VC-NONE", "x"));
    }

    @Test void tk04_hashIsSha256Hex() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", DeviceTokens.hash("abc"));
    }

    @Test void ns01_noShowSweepMarksTheDueVisits() {
        AppointmentService appointments = mock(AppointmentService.class);
        when(appointments.markNoShowsDue()).thenReturn(2);
        new NoShowSweep(appointments).sweep();
        verify(appointments).markNoShowsDue();
    }
}
