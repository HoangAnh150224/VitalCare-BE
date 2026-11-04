package com.vn.vitalcare.care.iomt;

import java.util.List;

/** What an upload came to, reading by reading — the sender drops from its queue whatever is not REJECTED. */
public record SyncResult(Long batchId, String status, int accepted, int duplicates, int invalid, int rejected,
                         List<RecordResult> records) {

    /**
     * @param outcome ACCEPTED (stored), DUPLICATE (already stored), INVALID (stored but implausible, not shown
     *                as a measurement), REJECTED (not stored; kept as a sync failure to retry)
     */
    public record RecordResult(String clientRecordId, String outcome, String reason, Long vitalId) {
    }
}
