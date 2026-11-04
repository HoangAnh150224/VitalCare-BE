package com.vn.vitalcare.care.staff.service.impl;

import com.vn.vitalcare.care.staff.service.ClinicScope;
import com.vn.vitalcare.identity.rowlevel.service.RowLevelPrincipalResolver;
import com.vn.vitalcare.share.security.rowlevel.RowLevelPrincipal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The {@link ClinicScope} the application runs on: the clinic on the caller's
 * staff record, as {@link StaffClinicPlacement} put it on the principal.
 *
 * <p>Read from the authority cache rather than queried per call; creating a
 * member of staff evicts their entry, so the answer is current from their
 * first request.
 */
@Service
public class ClinicScopeImpl implements ClinicScope {

    private final RowLevelPrincipalResolver principals;

    public ClinicScopeImpl(RowLevelPrincipalResolver principals) {
        this.principals = principals;
    }

    @Override
    public Optional<UUID> currentClinicId() {
        return principals.current().map(RowLevelPrincipal::clinicId);
    }
}
