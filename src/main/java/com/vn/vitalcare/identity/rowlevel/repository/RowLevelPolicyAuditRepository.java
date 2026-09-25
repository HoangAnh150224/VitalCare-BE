package com.vn.vitalcare.identity.rowlevel.repository;

import com.vn.vitalcare.identity.rowlevel.entity.RowLevelPolicyAudit;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Append-only. Nothing here updates or deletes, and nothing reads it back yet.
 *
 * <p>The trail is written on every change so that it exists when it is needed —
 * which, for an audit trail, is always after the fact.
 */
public interface RowLevelPolicyAuditRepository extends JpaRepository<RowLevelPolicyAudit, Long> {
}
