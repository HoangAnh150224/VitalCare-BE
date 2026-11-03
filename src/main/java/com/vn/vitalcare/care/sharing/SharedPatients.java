package com.vn.vitalcare.care.sharing;

import com.vn.vitalcare.entity.SharingPermission;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import com.vn.vitalcare.share.security.CurrentUser;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.function.Predicate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The caregiver's side (BF-06 Shared Patient Information): the patients who
 * shared with this account, each read checked against a grant in force and the
 * part asked for — vital signs, alerts or appointments. Revoked or expired, it
 * is a 404 at once.
 */
@Service
@Transactional(readOnly = true)
public class SharedPatients {

    private final SharingPermissionRepository permissions;
    private final Clock clock;

    public SharedPatients(SharingPermissionRepository permissions, Clock clock) {
        this.permissions = permissions;
        this.clock = clock;
    }

    public List<SharingPermission> mine() {
        return permissions.findInForceFor(me(), OffsetDateTime.now(clock));
    }

    /** The grant in force for this patient that covers {@code part}, or a 404. */
    public SharingPermission require(Long customerId, Predicate<SharingPermission> part) {
        return mine().stream()
                .filter(p -> p.getCustomer().getId().equals(customerId) && part.test(p))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Shared patient", customerId));
    }

    private static Long me() {
        return CurrentUser.id().orElseThrow();
    }
}
