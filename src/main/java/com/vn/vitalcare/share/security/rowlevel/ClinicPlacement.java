package com.vn.vitalcare.share.security.rowlevel;

import java.util.Optional;
import java.util.UUID;

/**
 * Which clinic an account works at, for the authority cache to put on the
 * principal as {@code user.clinicId}.
 *
 * <p>The framework loads placement straight off the account
 * ({@code user.getOrganization()}); here the clinic lives on the staff record,
 * which belongs to the care domain. This interface is how that domain answers
 * without identity depending on it — the same one-way direction
 * {@link RowLevelGeneration} runs.
 *
 * <p>Asked when the cache loads an account, never per request. Whoever changes
 * the answer must publish {@code AuthoritiesChanged.ForUser} for the account,
 * as the framework requires for organization and department moves.
 */
public interface ClinicPlacement {

    /** The account's clinic; empty when it has no staff record or the record has no clinic. */
    Optional<UUID> clinicOf(long userId);
}
