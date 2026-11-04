package com.vn.vitalcare.care.iomt;

import com.vn.vitalcare.entity.SyncBatch;
import com.vn.vitalcare.entity.SyncFailure;
import com.vn.vitalcare.share.data.BaseEntitySpecifications;
import com.vn.vitalcare.share.exception.ConflictException;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import com.vn.vitalcare.share.security.Permissions;
import com.vn.vitalcare.share.security.rowlevel.Action;
import com.vn.vitalcare.share.web.ListParams;
import com.vn.vitalcare.share.web.ListResponse;
import com.vn.vitalcare.identity.rowlevel.service.RowLevelSecurity;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * FE-06: {@code /api/device_syncs} — every upload and what came of it
 * (Synchronization History, the offline queue the devices report), and
 * {@code /api/sync_failures} — the readings refused, to look at and retry
 * (Synchronization Management, Failure Details). Both by the device's clinic.
 */
@RestController
public class SyncController {

    private static final Set<String> SORTABLE = Set.of("id", "receivedAt", "status", "rejectedCount");
    private static final Set<String> FAILURE_SORTABLE = Set.of("id", "lastAttemptAt", "attempts", "status");

    private final SyncBatchRepository batches;
    private final SyncFailureRepository failures;
    private final VitalIngestion ingestion;
    private final RowLevelSecurity rowLevel;
    private final ObjectMapper json;
    private final Clock clock;

    public SyncController(SyncBatchRepository batches, SyncFailureRepository failures, VitalIngestion ingestion,
                          RowLevelSecurity rowLevel, ObjectMapper json, Clock clock) {
        this.batches = batches;
        this.failures = failures;
        this.ingestion = ingestion;
        this.rowLevel = rowLevel;
        this.json = json;
        this.clock = clock;
    }

    public record BatchResponse(Long id, Long deviceId, String deviceCode, java.util.UUID clinicId, Long customerId,
                                String customerName, String clientBatchId, String source, OffsetDateTime receivedAt,
                                int recordCount, int acceptedCount, int duplicateCount, int rejectedCount,
                                Integer pendingOnDevice, String status, String error) {

        static BatchResponse of(SyncBatch b) {
            return new BatchResponse(b.getId(), b.getDevice() == null ? null : b.getDevice().getId(),
                    b.getDevice() == null ? null : b.getDevice().getDeviceCode(), b.getClinicId(),
                    b.getCustomer() == null ? null : b.getCustomer().getId(),
                    b.getCustomer() == null ? null : b.getCustomer().getFullName(), b.getClientBatchId(),
                    b.getSource(), b.getReceivedAt(), b.getRecordCount(), b.getAcceptedCount(),
                    b.getDuplicateCount(), b.getRejectedCount(), b.getPendingOnDevice(), b.getStatus(), b.getError());
        }
    }

    public record FailureResponse(Long id, Long batchId, Long deviceId, String deviceCode, String clientRecordId,
                                  JsonNode payload, String reason, String status, int attempts,
                                  OffsetDateTime lastAttemptAt, OffsetDateTime resolvedAt, OffsetDateTime receivedAt) {
    }

    // ---- history ----------------------------------------------------------------------

    @GetMapping("/api/device_syncs")
    @PreAuthorize("hasAuthority('" + Permissions.DEVICE_SYNCS_READ + "')")
    public ResponseEntity<List<BatchResponse>> batches(@RequestParam MultiValueMap<String, String> query) {
        ListParams params = new ListParams(query);
        Page<SyncBatch> page = batches.findAll(
                rowLevel.scope(SyncBatch.class, Action.READ).and(batchFilter(params)),
                params.pageable(SORTABLE, Sort.by(Sort.Order.desc("receivedAt"), Sort.Order.desc("id"))));
        return ListResponse.of(page.getContent().stream().map(BatchResponse::of).toList(), page.getTotalElements());
    }

    @GetMapping("/api/device_syncs/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.DEVICE_SYNCS_READ + "')")
    public BatchResponse batch(@PathVariable Long id) {
        return BatchResponse.of(batch(id, Action.READ));
    }

    /** The readings refused in one upload. */
    @GetMapping("/api/device_syncs/{id}/failures")
    @PreAuthorize("hasAuthority('" + Permissions.DEVICE_SYNCS_READ + "')")
    public List<FailureResponse> batchFailures(@PathVariable Long id) {
        batch(id, Action.READ);
        return failures.findAll(Specification.allOf(rowLevel.scope(SyncFailure.class, Action.READ),
                        (root, q, cb) -> cb.equal(root.get("batch").get("id"), id)))
                .stream().map(this::failure).toList();
    }

    // ---- failures ---------------------------------------------------------------------

    @GetMapping("/api/sync_failures")
    @PreAuthorize("hasAuthority('" + Permissions.SYNC_FAILURES_READ + "')")
    public ResponseEntity<List<FailureResponse>> failures(@RequestParam MultiValueMap<String, String> query) {
        ListParams params = new ListParams(query);
        Page<SyncFailure> page = failures.findAll(
                rowLevel.scope(SyncFailure.class, Action.READ).and(failureFilter(params)),
                params.pageable(FAILURE_SORTABLE, Sort.by(Sort.Order.desc("lastAttemptAt"), Sort.Order.desc("id"))));
        return ListResponse.of(page.getContent().stream().map(this::failure).toList(), page.getTotalElements());
    }

    @GetMapping("/api/sync_failures/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.SYNC_FAILURES_READ + "')")
    public FailureResponse failure(@PathVariable Long id) {
        return failure(failure(id, Action.READ));
    }

    /** Through the intake again; still OPEN, with the new reason, if it is refused again. */
    @PostMapping("/api/sync_failures/{id}/retry")
    @PreAuthorize("hasAuthority('" + Permissions.SYNC_FAILURES_WRITE + "')")
    @Transactional
    public FailureResponse retry(@PathVariable Long id) {
        SyncFailure failure = open(id);
        return failure(ingestion.retry(failure));
    }

    /** Set aside: not to be taken in (a reading known to be wrong). */
    @PostMapping("/api/sync_failures/{id}/discard")
    @PreAuthorize("hasAuthority('" + Permissions.SYNC_FAILURES_WRITE + "')")
    @Transactional
    public FailureResponse discard(@PathVariable Long id) {
        SyncFailure failure = open(id);
        failure.discard(OffsetDateTime.now(clock));
        return failure(failure);
    }

    private SyncFailure open(Long id) {
        SyncFailure failure = failure(id, Action.WRITE);
        if (!SyncFailure.OPEN.equals(failure.getStatus())) {
            throw new ConflictException("This record has already been dealt with");
        }
        return failure;
    }

    private SyncBatch batch(Long id, Action action) {
        return batches.findOne(Specification.allOf(rowLevel.byId(SyncBatch.class, action, id),
                        BaseEntitySpecifications.notDeleted()))
                .orElseThrow(() -> new ResourceNotFoundException("Sync batch", id));
    }

    private SyncFailure failure(Long id, Action action) {
        return failures.findOne(Specification.allOf(rowLevel.byId(SyncFailure.class, action, id),
                        BaseEntitySpecifications.notDeleted()))
                .orElseThrow(() -> new ResourceNotFoundException("Sync failure", id));
    }

    private FailureResponse failure(SyncFailure f) {
        JsonNode payload;
        try {
            payload = json.readTree(f.getPayload());
        } catch (RuntimeException e) {
            payload = null;
        }
        return new FailureResponse(f.getId(), f.getBatch().getId(),
                f.getDevice() == null ? null : f.getDevice().getId(),
                f.getDevice() == null ? null : f.getDevice().getDeviceCode(), f.getClientRecordId(), payload,
                f.getReason(), f.getStatus(), f.getAttempts(), f.getLastAttemptAt(), f.getResolvedAt(),
                f.getBatch().getReceivedAt());
    }

    private static Specification<SyncBatch> batchFilter(ListParams params) {
        List<Specification<SyncBatch>> specs = new ArrayList<>();
        specs.add(BaseEntitySpecifications.notDeleted());
        for (ListParams.Criterion c : params.filters()) {
            String v = c.value() == null ? "" : c.value().trim();
            switch (c.field()) {
                case "status", "source" -> specs.add((root, q, cb) -> cb.equal(root.get(c.field()), v.toUpperCase()));
                case "deviceId" -> specs.add(idEquals("device", v));
                case "customerId" -> specs.add(idEquals("customer", v));
                // The offline queue: uploads whose device said it still holds readings back.
                case "pendingOnDevice" -> specs.add((root, q, cb) -> cb.greaterThan(root.get("pendingOnDevice"), 0));
                default -> {
                }
            }
        }
        params.search().ifPresent(term -> specs.add((root, q, cb) -> cb.or(
                cb.like(cb.lower(root.join("device").get("deviceCode")), ListParams.likePattern(term)),
                cb.like(cb.lower(root.get("clientBatchId")), ListParams.likePattern(term)))));
        return Specification.allOf(specs);
    }

    private static Specification<SyncFailure> failureFilter(ListParams params) {
        List<Specification<SyncFailure>> specs = new ArrayList<>();
        specs.add(BaseEntitySpecifications.notDeleted());
        for (ListParams.Criterion c : params.filters()) {
            String v = c.value() == null ? "" : c.value().trim();
            switch (c.field()) {
                case "status" -> specs.add((root, q, cb) -> cb.equal(root.get("status"), v.toUpperCase()));
                case "deviceId" -> specs.add(idEquals("device", v));
                case "batchId" -> specs.add(idEquals("batch", v));
                default -> {
                }
            }
        }
        params.search().ifPresent(term -> specs.add((root, q, cb) -> cb.or(
                cb.like(cb.lower(root.join("device").get("deviceCode")), ListParams.likePattern(term)),
                cb.like(cb.lower(root.get("reason")), ListParams.likePattern(term)),
                cb.like(cb.lower(root.get("clientRecordId")), ListParams.likePattern(term)))));
        return Specification.allOf(specs);
    }

    private static <T> Specification<T> idEquals(String association, String value) {
        try {
            Long id = Long.valueOf(value);
            return (root, q, cb) -> cb.equal(root.get(association).get("id"), id);
        } catch (NumberFormatException e) {
            return (root, q, cb) -> cb.disjunction();
        }
    }
}
