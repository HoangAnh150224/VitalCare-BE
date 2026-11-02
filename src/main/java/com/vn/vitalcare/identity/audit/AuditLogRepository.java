package com.vn.vitalcare.identity.audit;

import com.vn.vitalcare.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** Rows are added and read; the application has no path that updates or deletes one (BR-24). */
public interface AuditLogRepository extends JpaRepository<AuditLog, Long>, JpaSpecificationExecutor<AuditLog> {
}
