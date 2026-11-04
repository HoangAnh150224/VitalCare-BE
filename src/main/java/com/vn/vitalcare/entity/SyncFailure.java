package com.vn.vitalcare.entity;

import com.vn.vitalcare.share.data.BaseEntity;
import com.vn.vitalcare.share.security.rowlevel.RowLevelResource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A record an upload could not take in (FE-06 Synchronization Failure
 * Details): the record as it came, why, and how many times it has been tried.
 * Retrying runs it through the same intake again — it succeeds once whatever
 * stopped it (the device not yet handed to anybody, say) is put right.
 */
@Entity
@RowLevelResource("sync_failures")
@Table(name = "sync_failure")
public class SyncFailure extends BaseEntity {

    public static final String OPEN = "OPEN";
    public static final String RESOLVED = "RESOLVED";
    public static final String DISCARDED = "DISCARDED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "batch_id", nullable = false)
    private SyncBatch batch;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "device_id")
    private MedicalDevice device;

    @Column(name = "clinic_id")
    private UUID clinicId;

    @Column(name = "client_record_id", length = 64)
    private String clientRecordId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "reason", nullable = false, length = 255)
    private String reason;

    @Column(name = "status", nullable = false, length = 16)
    private String status = OPEN;

    @Column(name = "attempts", nullable = false)
    private int attempts = 1;

    @Column(name = "last_attempt_at", nullable = false)
    private OffsetDateTime lastAttemptAt;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    protected SyncFailure() {
    }

    public SyncFailure(SyncBatch batch, MedicalDevice device, String clientRecordId, String payload, String reason,
                       OffsetDateTime when) {
        this.batch = batch;
        this.device = device;
        this.clinicId = device == null ? null : device.getClinicId();
        this.clientRecordId = clientRecordId;
        this.payload = payload;
        this.reason = reason == null || reason.length() <= 255 ? reason : reason.substring(0, 255);
        this.lastAttemptAt = when;
    }

    public void triedAgain(String reason, OffsetDateTime when) {
        this.attempts++;
        this.lastAttemptAt = when;
        this.reason = reason == null || reason.length() <= 255 ? reason : reason.substring(0, 255);
    }

    public void resolve(OffsetDateTime when) {
        this.status = RESOLVED;
        this.resolvedAt = when;
        this.lastAttemptAt = when;
    }

    public void discard(OffsetDateTime when) {
        this.status = DISCARDED;
        this.resolvedAt = when;
    }

    public Long getId() {
        return id;
    }

    public SyncBatch getBatch() {
        return batch;
    }

    public MedicalDevice getDevice() {
        return device;
    }

    public UUID getClinicId() {
        return clinicId;
    }

    public String getClientRecordId() {
        return clientRecordId;
    }

    public String getPayload() {
        return payload;
    }

    public String getReason() {
        return reason;
    }

    public String getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public OffsetDateTime getLastAttemptAt() {
        return lastAttemptAt;
    }

    public OffsetDateTime getResolvedAt() {
        return resolvedAt;
    }
}
