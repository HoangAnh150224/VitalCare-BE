--liquibase formatted sql

--changeset codex:079-clinic-admin-medical-services
-- Clinic administrators maintain the shared service catalogue, including pause/resume.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code = 'medical_services:write'
WHERE r.code = 'CLINIC_ADMIN'
  AND NOT EXISTS (
    SELECT 1 FROM role_permissions rp
    WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );
