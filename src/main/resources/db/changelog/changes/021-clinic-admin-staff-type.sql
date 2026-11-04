--liquibase formatted sql

--changeset vitalcare:021-clinic-admin-staff-type
--comment The clinic administrator becomes a kind of staff: CLINIC_ADMIN is a system role the code assigns by code.

-- Creating staff of type clinic_admin gives the account CLINIC_ADMIN by its
-- code, the way a doctor gets DOCTOR. A role the code names must exist and
-- must not be renamed or deleted on the Roles screen, so it is a system role
-- from here on -- and is created where it was not made by hand yet.
INSERT INTO roles (code, name, description, system_role)
SELECT 'CLINIC_ADMIN', 'Quản trị phòng khám',
       'Quản trị một cơ sở: nhân viên, bệnh nhân, lịch hẹn, thiết bị trong cơ sở của mình. Phải có hồ sơ nhân viên gắn cơ sở.',
       TRUE
WHERE NOT EXISTS (SELECT 1 FROM roles WHERE code = 'CLINIC_ADMIN');

UPDATE roles SET system_role = TRUE WHERE code = 'CLINIC_ADMIN';

-- Its starting grants, only for a role that has none: where it was made on
-- the Roles screen, what was granted there stands.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN ('appointments:check_in', 'appointments:read', 'appointments:write', 'clinics:read',
                                 'customers:activate', 'customers:assign', 'customers:read',
                                 'devices:read', 'devices:write', 'employees:read', 'employees:write')
WHERE r.code = 'CLINIC_ADMIN'
  AND NOT EXISTS (SELECT 1 FROM role_permissions rp WHERE rp.role_id = r.id);

-- Its clinic scopes, likewise only where it has none. 020 skipped them on a
-- database where the role did not exist yet.
INSERT INTO row_level_policy (kind, role_id, resource, action, name, policy_group, description, scope, enabled, created_at)
SELECT 'SCOPE', r.id, v.resource, v.action, 'Cơ sở của mình', 'clinic', v.description,
       '{"field": "clinicId", "op": "eq", "value": {"ctx": "user.clinicId"}}'::jsonb, TRUE, now()
FROM (VALUES
  ('employees',    'read',  'Nhân viên cùng cơ sở.'),
  ('employees',    'write', 'Thêm và sửa nhân viên trong cơ sở của mình.'),
  ('appointments', 'read',  'Lịch hẹn tại cơ sở của mình.'),
  ('appointments', 'write', 'Đặt, check-in và huỷ lịch hẹn tại cơ sở của mình.'),
  ('devices',      'read',  'Thiết bị của cơ sở mình.'),
  ('devices',      'write', 'Đăng ký và sửa thiết bị của cơ sở mình.')
) AS v(resource, action, description)
JOIN roles r ON r.code = 'CLINIC_ADMIN'
WHERE NOT EXISTS (SELECT 1 FROM row_level_policy p WHERE p.role_id = r.id);

-- Administrators made before this existed were created as front desk and then
-- given the role: their staff record says so too now.
UPDATE employee e SET staff_type = 'CLINIC_ADMIN'
WHERE e.staff_type = 'RECEPTIONIST'
  AND EXISTS (SELECT 1 FROM user_roles ur JOIN roles r ON r.id = ur.role_id
              WHERE ur.user_id = e.user_id AND r.code = 'CLINIC_ADMIN');

--rollback UPDATE employee SET staff_type = 'RECEPTIONIST' WHERE staff_type = 'CLINIC_ADMIN';
--rollback UPDATE roles SET system_role = FALSE WHERE code = 'CLINIC_ADMIN';
