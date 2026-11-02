package com.vn.vitalcare.care.iomt;

import com.vn.vitalcare.care.device.repository.MedicalDeviceRepository;
import com.vn.vitalcare.entity.MedicalDevice;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The secret a device signs in with — to MQTT as username = its code, password
 * = this; over REST in {@code X-Device-Code}/{@code X-Device-Token}. Only its
 * SHA-256 is kept: shown once when issued, a lost one is replaced, not read back.
 */
@Service
public class DeviceTokens {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final MedicalDeviceRepository devices;
    private final com.vn.vitalcare.care.device.service.DeviceService deviceService;

    public DeviceTokens(MedicalDeviceRepository devices,
                        com.vn.vitalcare.care.device.service.DeviceService deviceService) {
        this.devices = devices;
        this.deviceService = deviceService;
    }

    /**
     * A new secret for a device the caller may change, locked through the caller's write scope for the
     * whole of it; the old secret stops working.
     */
    @Transactional
    public Issued issue(Long deviceId) {
        MedicalDevice device = deviceService.lockForUpdate(deviceId);
        return new Issued(device.getDeviceCode(), issue(device));
    }

    public record Issued(String deviceCode, String token) {
    }

    private String issue(MedicalDevice device) {
        byte[] raw = new byte[24];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        device.setTokenHash(hash(token));
        devices.persist(device);
        return token;
    }

    /** Whether the secret is the device's, compared in constant time. */
    @Transactional(readOnly = true)
    public boolean verify(String deviceCode, String token) {
        if (deviceCode == null || token == null || token.isBlank()) {
            return false;
        }
        return devices.findByCode(deviceCode.trim().toUpperCase(Locale.ROOT))
                .map(MedicalDevice::getTokenHash)
                .map(stored -> MessageDigest.isEqual(stored.getBytes(StandardCharsets.US_ASCII),
                        hash(token.trim()).getBytes(StandardCharsets.US_ASCII)))
                .orElse(false);
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
