package com.vn.vitalcare.identity.rowlevel.repository;

import com.vn.vitalcare.identity.rowlevel.entity.RowLevelPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * A plain {@code JpaRepository}, not a {@code SecuredRepository}.
 *
 * <p>The policy table is not itself under row-level management, and making it so
 * would be circular: deciding which policies you may see would need the policies
 * you may see. Access to it is settled entirely by
 * {@code row_level_policies:read|write|delete}, which the seed grants to
 * {@code ADMIN} and to nobody else.
 */
public interface RowLevelPolicyRepository
        extends JpaRepository<RowLevelPolicy, Long>, JpaSpecificationExecutor<RowLevelPolicy> {
}
