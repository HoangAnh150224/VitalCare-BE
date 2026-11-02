package com.vn.vitalcare.care.iomt;

import com.vn.vitalcare.care.device.service.DeviceService;
import com.vn.vitalcare.entity.MedicalDevice;
import com.vn.vitalcare.share.exception.FieldValidationException;
import com.vn.vitalcare.share.security.Permissions;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What an administrator does to a device beyond its record: issue its MQTT
 * secret, and feed it simulated readings to demonstrate and test the
 * monitoring chain without the hardware.
 */
@RestController
@RequestMapping("/api/devices")
public class DeviceOperationsController {

    private final DeviceService devices;
    private final DeviceTokens tokens;
    private final VitalIngestion ingestion;
    private final MqttBridge mqtt;
    private final Clock clock;

    public DeviceOperationsController(DeviceService devices, DeviceTokens tokens, VitalIngestion ingestion,
                                      MqttBridge mqtt, Clock clock) {
        this.devices = devices;
        this.tokens = tokens;
        this.ingestion = ingestion;
        this.mqtt = mqtt;
        this.clock = clock;
    }

    /** A new secret, shown this once; the device's old one stops working. */
    @PostMapping("/{id}/token")
    @PreAuthorize("hasAuthority('" + Permissions.DEVICES_OPERATE + "')")
    public Map<String, String> issueToken(@PathVariable Long id) {
        DeviceTokens.Issued issued = tokens.issue(id);
        return Map.of("deviceCode", issued.deviceCode(), "token", issued.token(),
                "mqttUsername", issued.deviceCode(),
                "mqttTopic", com.vn.vitalcare.config.MqttProperties.TOPIC_ROOT + "/" + issued.deviceCode() + "/vitals");
    }

    @GetMapping("/mqtt")
    @PreAuthorize("hasAuthority('" + Permissions.DEVICES_OPERATE + "')")
    public Map<String, Object> mqttState() {
        return mqtt.state();
    }

    /**
     * {@code scenario}: NORMAL, LOW_SPO2, HIGH_HEART_RATE, FEVER (a high skin
     * temperature) or SENSOR_FAULT (implausible values, stored as INVALID).
     */
    public record SimulationRequest(Integer count, Integer intervalSeconds, String scenario,
                                    BigDecimal spo2, Integer heartRate, BigDecimal skinTemperature) {
    }

    @PostMapping("/{id}/simulate")
    @PreAuthorize("hasAuthority('" + Permissions.DEVICES_OPERATE + "')")
    public SyncResult simulate(@PathVariable Long id, @RequestBody SimulationRequest request) {
        MedicalDevice device = devices.get(id);
        int count = request.count() == null ? 5 : request.count();
        if (count < 1 || count > 120) {
            throw new FieldValidationException("count", "Between 1 and 120 readings");
        }
        long intervalMs = 1000L * (request.intervalSeconds() == null ? 60
                : Math.max(5, Math.min(request.intervalSeconds(), 3600)));
        // Kept inside the current wearer's time with the device, so a band handed over a minute ago still
        // takes them all (spread over that minute); with nobody wearing it they are refused, as a real
        // device's would be.
        var wearing = devices.openAssignments(List.of(device.getId())).get(device.getId());
        if (wearing != null && wearing.getAssignedAt() != null && count > 1) {
            long sinceMs = java.time.Duration.between(wearing.getAssignedAt(), OffsetDateTime.now(clock)).toMillis();
            intervalMs = Math.min(intervalMs, Math.max(0, sinceMs - 1) / (count - 1));
        }
        String scenario = request.scenario() == null ? "NORMAL" : request.scenario().toUpperCase(java.util.Locale.ROOT);

        BigDecimal spo2 = request.spo2() != null ? request.spo2() : switch (scenario) {
            case "LOW_SPO2" -> new BigDecimal("88");
            case "SENSOR_FAULT" -> new BigDecimal("140");
            default -> new BigDecimal("97");
        };
        int heartRate = request.heartRate() != null ? request.heartRate() : switch (scenario) {
            case "HIGH_HEART_RATE" -> 135;
            case "SENSOR_FAULT" -> 0;
            default -> 76;
        };
        BigDecimal skin = request.skinTemperature() != null ? request.skinTemperature()
                : "FEVER".equals(scenario) ? new BigDecimal("38.6") : new BigDecimal("33.4");

        ThreadLocalRandom random = ThreadLocalRandom.current();
        OffsetDateTime now = OffsetDateTime.now(clock);
        String session = "sim-" + UUID.randomUUID();
        List<DeviceUpload.Reading> readings = new ArrayList<>(count);
        for (int i = count - 1; i >= 0; i--) {
            boolean fault = "SENSOR_FAULT".equals(scenario);
            readings.add(new DeviceUpload.Reading(
                    "sim-" + UUID.randomUUID(),
                    now.minus(java.time.Duration.ofMillis(i * intervalMs)).toString(),
                    fault ? spo2 : spo2.add(BigDecimal.valueOf(random.nextInt(-10, 11), 1)).min(new BigDecimal("100"))
                            .setScale(1, RoundingMode.HALF_UP),
                    fault ? heartRate : heartRate + random.nextInt(-3, 4),
                    skin.add(BigDecimal.valueOf(random.nextInt(-2, 3), 1)).setScale(1, RoundingMode.HALF_UP),
                    session));
        }
        DeviceUpload upload = new DeviceUpload("sim-" + now.toEpochSecond(), 0, random.nextInt(40, 101), "sim-1.0",
                new DeviceUpload.Session(session, now.minus(java.time.Duration.ofMillis((count - 1) * intervalMs)).toString(),
                        now.toString(), "COMPLETED"),
                readings);
        return ingestion.ingest(device.getDeviceCode(), upload, "SIMULATOR");
    }
}
