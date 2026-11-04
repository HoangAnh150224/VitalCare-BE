package com.vn.vitalcare.entity;

import com.vn.vitalcare.share.data.BaseEntity;
import com.vn.vitalcare.share.security.rowlevel.RowLevelResource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@RowLevelResource("devices")
@Table(name = "medical_device")
public class MedicalDevice extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "clinic_id")
    private Clinic clinic;

    /**
     * The same column as {@link #clinic}, as a plain value, for row-level
     * policies: they compare scalar columns, and a clinic is keyed by a UUID
     * the engine's references cannot address. Read-only here; {@link #setClinic}
     * keeps it in step so a policy checking a row before it is saved sees the
     * clinic it is about to have.
     */
    @Column(name = "clinic_id", insertable = false, updatable = false)
    private UUID clinicId;

    @Column(name = "device_code", nullable = false, unique = true)
    private String deviceCode;

    @Column(name = "serial_number")
    private String serialNumber;

    @Column(name = "device_type")
    private String deviceType;

    @Column(name = "manufacturer")
    private String manufacturer;

    @Column(name = "model")
    private String model;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private DeviceStatus status = DeviceStatus.AVAILABLE;

    @Column(name = "last_seen_at")
    private OffsetDateTime lastSeenAt;

    @Column(name = "registered_at")
    private OffsetDateTime registeredAt;

    public MedicalDevice() {
    }

    /** Battery as the device last reported it, 0-100; null until it has. */
    @Column(name = "battery_level")
    private Integer batteryLevel;

    @Column(name = "firmware_version", length = 64)
    private String firmwareVersion;

    /** {@code ONLINE} while it keeps reporting, {@code OFFLINE} after a quiet spell; see DeviceHeartbeat. */
    @Column(name = "connectivity", nullable = false, length = 16)
    private String connectivity = OFFLINE;

    /** When its readings last arrived. */
    @Column(name = "last_sync_at")
    private OffsetDateTime lastSyncAt;

    /** SHA-256 of the secret it signs in to MQTT with; the secret itself is shown once and never kept. */
    @Column(name = "token_hash", length = 64)
    private String tokenHash;

    /** When the patient's phone paired with it over BLE (FE-02 Device Pairing). */
    @Column(name = "paired_at")
    private OffsetDateTime pairedAt;

    @Column(name = "paired_by_user_id")
    private Long pairedByUserId;

    public static final String ONLINE = "ONLINE";
    public static final String OFFLINE = "OFFLINE";

    /** It reported: online, and what it said about itself. */
    public void heardFrom(OffsetDateTime when, Integer battery, String firmware) {
        this.lastSeenAt = when;
        this.connectivity = ONLINE;
        if (battery != null) {
            this.batteryLevel = Math.max(0, Math.min(100, battery));
        }
        if (firmware != null && !firmware.isBlank()) {
            this.firmwareVersion = firmware.trim().length() > 64 ? firmware.trim().substring(0, 64) : firmware.trim();
        }
    }

    public Integer getBatteryLevel() {
        return batteryLevel;
    }

    public String getFirmwareVersion() {
        return firmwareVersion;
    }

    public String getConnectivity() {
        return connectivity;
    }

    public void setConnectivity(String connectivity) {
        this.connectivity = connectivity;
    }

    public OffsetDateTime getLastSyncAt() {
        return lastSyncAt;
    }

    public void setLastSyncAt(OffsetDateTime lastSyncAt) {
        this.lastSyncAt = lastSyncAt;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public void setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
    }

    public OffsetDateTime getPairedAt() {
        return pairedAt;
    }

    public Long getPairedByUserId() {
        return pairedByUserId;
    }

    public void pair(Long userId, OffsetDateTime when) {
        this.pairedByUserId = userId;
        this.pairedAt = when;
    }

    public void unpair() {
        this.pairedByUserId = null;
        this.pairedAt = null;
    }

    public Long getId() {
        return id;
    }

    public Clinic getClinic() {
        return clinic;
    }

    public void setClinic(Clinic clinic) {
        this.clinic = clinic;
        this.clinicId = clinic == null ? null : clinic.getClinicId();
    }

    public UUID getClinicId() {
        return clinicId;
    }

    public String getDeviceCode() {
        return deviceCode;
    }

    public void setDeviceCode(String deviceCode) {
        this.deviceCode = deviceCode;
    }

    public String getSerialNumber() {
        return serialNumber;
    }

    public void setSerialNumber(String serialNumber) {
        this.serialNumber = serialNumber;
    }

    public String getDeviceType() {
        return deviceType;
    }

    public void setDeviceType(String deviceType) {
        this.deviceType = deviceType;
    }

    public String getManufacturer() {
        return manufacturer;
    }

    public void setManufacturer(String manufacturer) {
        this.manufacturer = manufacturer;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public DeviceStatus getStatus() {
        return status;
    }

    public void setStatus(DeviceStatus status) {
        this.status = status;
    }

    public OffsetDateTime getLastSeenAt() {
        return lastSeenAt;
    }

    public void setLastSeenAt(OffsetDateTime lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }

    public OffsetDateTime getRegisteredAt() {
        return registeredAt;
    }

    public void setRegisteredAt(OffsetDateTime registeredAt) {
        this.registeredAt = registeredAt;
    }
}
