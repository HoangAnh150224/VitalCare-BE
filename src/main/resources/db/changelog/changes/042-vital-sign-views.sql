--liquibase formatted sql

--changeset vitalcare:042-vital-sign-views
--comment FE-04: a patient reads their own vital signs and their dependants' in the portal and the app.

INSERT INTO permissions (code, name, description, system_permission) VALUES
  ('my_vital_signs:read', 'View own vital signs', 'See the vital signs measured for one''s own profiles', TRUE);

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code = 'my_vital_signs:read'
WHERE r.code IN ('CUSTOMER', 'PATIENT')
  AND NOT EXISTS (SELECT 1 FROM role_permissions x WHERE x.role_id = r.id AND x.permission_id = p.id);

--rollback DELETE FROM role_permissions WHERE permission_id IN (SELECT id FROM permissions WHERE code = 'my_vital_signs:read');
--rollback DELETE FROM permissions WHERE code = 'my_vital_signs:read';
