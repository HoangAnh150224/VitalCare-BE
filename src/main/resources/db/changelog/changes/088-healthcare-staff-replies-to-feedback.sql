--liquibase formatted sql

--changeset vitalcare:088-healthcare-staff-replies-to-feedback
--comment Healthcare staff (MANAGER) reply to patient feedback alongside the clinic admin, within the clinic they can read. Removing feedback stays with the clinic admin.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.code = 'MANAGER' AND p.code = 'feedback:write'
AND NOT EXISTS (SELECT 1 FROM role_permissions rp WHERE rp.role_id = r.id AND rp.permission_id = p.id);
--rollback DELETE FROM role_permissions WHERE role_id IN (SELECT id FROM roles WHERE code = 'MANAGER') AND permission_id IN (SELECT id FROM permissions WHERE code = 'feedback:write');
