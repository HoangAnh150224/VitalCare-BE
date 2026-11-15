--liquibase formatted sql
--changeset codex:080-feedback-management
ALTER TABLE patient_feedback ADD COLUMN reply varchar(2000), ADD COLUMN replied_at timestamptz, ADD COLUMN removal_reason varchar(500);
INSERT INTO permissions (code, name, description, system_permission)
VALUES ('feedback:write', 'Reply to feedback', 'Reply to patient feedback within readable clinic scope', true),
       ('feedback:delete', 'Moderate feedback', 'Soft delete inappropriate feedback with a reason', true)
ON CONFLICT (code) DO NOTHING;
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.code = 'CLINIC_ADMIN' AND p.code IN ('feedback:write', 'feedback:delete')
AND NOT EXISTS (SELECT 1 FROM role_permissions rp WHERE rp.role_id = r.id AND rp.permission_id = p.id);
