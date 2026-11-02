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

/**
 * One upload of readings from a device (FE-06 Synchronization History): how
 * many came, were kept, were already there, or were refused, and what the
 * device said it still holds offline.
 */
@Entity
@RowLevelResource("device_syncs")
@Table(name = "sync_batch")
public class SyncBatch extends BaseEntity {

    public static final String SUCCESS = "SUCCESS";
    public static final String PARTIAL = "PARTIAL";
    public static final String FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "device_id")
    private MedicalDevice device;

    @Column(name = "clinic_id")
    private UUID clinicId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @Column(name = "client_batch_id", length = 64)
    private String clientBatchId;

    @Column(name = "source", nullable = false, length = 16)
    private String source;

    @Column(name = "received_at", nullable = false)
    private OffsetDateTime receivedAt;

    @Column(name = "record_count", nullable = false)
    private int recordCount;

    @Column(name = "accepted_count", nullable = false)
    private int acceptedCount;

    @Column(name = "duplicate_count", nullable = false)
    private int duplicateCount;

    @Column(name = "rejected_count", nullable = false)
    private int rejectedCount;

    /** What the device said it still holds, waiting to be sent: the offline queue (FE-06). */
    @Column(name = "pending_on_device")
    private Integer pendingOnDevice;

    @Column(name = "status", nullable = false, length = 16)
    private String status = SUCCESS;

    @Column(name = "error", length = 500)
    private String error;

    protected SyncBatch() {
    }

    public SyncBatch(MedicalDevice device, String source, String clientBatchId, int recordCount,
                     Integer pendingOnDevice, OffsetDateTime receivedAt) {
        this.device = device;
        this.clinicId = device == null ? null : device.getClinicId();
        this.source = source;
        this.clientBatchId = clientBatchId;
        this.recordCount = recordCount;
        this.pendingOnDevice = pendingOnDevice;
        this.receivedAt = receivedAt;
    }

    public void accepted() {
        acceptedCount++;
    }

    public void duplicate() {
        duplicateCount++;
    }

    public void rejected() {
        rejectedCount++;
    }

    /** SUCCESS when nothing was refused, FAILED when nothing was taken, PARTIAL in between. */
    public void close(String error) {
        this.error = error == null || error.length() <= 500 ? error : error.substring(0, 500);
        if (rejectedCount == 0 && error == null) {
            status = SUCCESS;
        } else if (acceptedCount + duplicateCount == 0) {
            status = FAILED;
        } else {
            status = PARTIAL;
        }
    }

    /** A failed record of this batch was taken in on a retry — or found already stored meanwhile. */
    public void recovered(boolean alreadyStored) {
        if (rejectedCount > 0) {
            rejectedCount--;
            if (alreadyStored) {
                duplicateCount++;
            } else {
                acceptedCount++;
            }
        }
        close(rejectedCount == 0 ? null : error);
    }

    public void setCustomer(Customer customer) {
        this.customer = customer;
    }

    public Long getId() {
        return id;
    }

    public MedicalDevice getDevice() {
        return device;
    }

    public UUID getClinicId() {
        return clinicId;
    }

    public Customer getCustomer() {
        return customer;
    }

    public String getClientBatchId() {
        return clientBatchId;
    }

    public String getSource() {
        return source;
    }

    public OffsetDateTime getReceivedAt() {
        return receivedAt;
    }

    public int getRecordCount() {
        return recordCount;
    }

    public int getAcceptedCount() {
        return acceptedCount;
    }

    public int getDuplicateCount() {
        return duplicateCount;
    }

    public int getRejectedCount() {
        return rejectedCount;
    }

    public Integer getPendingOnDevice() {
        return pendingOnDevice;
    }

    public String getStatus() {
        return status;
    }

    public String getError() {
        return error;
    }
}
