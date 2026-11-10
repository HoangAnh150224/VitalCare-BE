--liquibase formatted sql

--changeset vitalcare:063-admin-grants-clinic-admin
--comment The System Administrator creates or grants the Clinic Administrator role (BR-AC-02). Creating one already worked; granting it to somebody already on staff, or taking it away, goes through the clinic-roles screen, which needs the staff list and clinic_roles - both taken from ADMIN by 052. Given back, read-only on staff: the service lets ADMIN change only the Clinic Administrator role there.

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN ('employees:read', 'clinic_roles:read', 'clinic_roles:write')
WHERE r.code = 'ADMIN'
  AND NOT EXISTS (SELECT 1 FROM role_permissions x WHERE x.role_id = r.id AND x.permission_id = p.id);

-- Every clinic's staff: the employee is looked up under the write scope when its roles are saved.
INSERT INTO row_level_policy (kind, role_id, resource, action, name, policy_group, description, scope, enabled, created_at)
SELECT 'SCOPE', r.id, 'employees', v.action, 'Mọi cơ sở', 'clinic', v.description, '{"all": []}'::jsonb, TRUE, now()
FROM roles r
CROSS JOIN (VALUES
    ('read',  'Quản trị hệ thống thấy nhân viên của mọi cơ sở.'),
    ('write', 'Quản trị hệ thống cấp hoặc gỡ vai trò Quản trị phòng khám ở mọi cơ sở.')
) AS v(action, description)
WHERE r.code = 'ADMIN'
  AND NOT EXISTS (SELECT 1 FROM row_level_policy x WHERE x.role_id = r.id AND x.resource = 'employees' AND x.action = v.action);

--rollback DELETE FROM row_level_policy WHERE resource = 'employees' AND role_id = (SELECT id FROM roles WHERE code = 'ADMIN');
--rollback DELETE FROM role_permissions WHERE role_id = (SELECT id FROM roles WHERE code = 'ADMIN') AND permission_id IN (SELECT id FROM permissions WHERE code IN ('employees:read', 'clinic_roles:read', 'clinic_roles:write'));
