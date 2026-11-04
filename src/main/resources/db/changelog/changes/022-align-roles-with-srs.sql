--liquibase formatted sql

--changeset vitalcare:022-align-roles-with-srs
--comment Role grants as the SRS screen-authorization matrix (3.1.3) has them.

-- "Assign Patient to Healthcare Staff" is the Clinic Administrator's alone;
-- the front desk keeps customers:assign, which is now only the device.
INSERT INTO permissions (code, name, description, system_permission) VALUES
  ('customers:assign_staff', 'Assign care team', 'Put staff on a patient''s care team, and take them off', TRUE);

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code = 'customers:assign_staff'
WHERE r.code = 'CLINIC_ADMIN';

-- The System Administrator runs clinics, staff, accounts and audit, not a
-- clinic's patients, appointments or devices. customers:read stays: the
-- read-only "view as" screens (view_as:read) list customers through it.
DELETE FROM role_permissions
WHERE role_id = (SELECT id FROM roles WHERE code = 'ADMIN')
  AND permission_id IN (SELECT id FROM permissions WHERE code IN (
        'appointments:read', 'appointments:write', 'appointments:check_in',
        'devices:read', 'devices:write',
        'customers:write', 'customers:activate', 'customers:assign'));

DELETE FROM row_level_policy
WHERE role_id = (SELECT id FROM roles WHERE code = 'ADMIN') AND resource IN ('appointments', 'devices');

-- Names as the SRS actors (2.1). Codes stay: the code assigns roles by them.
UPDATE roles SET name = 'System Administrator' WHERE code = 'ADMIN';
UPDATE roles SET name = 'Clinic Administrator' WHERE code = 'CLINIC_ADMIN';
UPDATE roles SET name = 'Healthcare Staff - Front Desk' WHERE code = 'MANAGER';
UPDATE roles SET name = 'Healthcare Staff - Nurse' WHERE code = 'NURSE';
UPDATE roles SET name = 'Guest / Patient' WHERE code = 'CUSTOMER';

--rollback DELETE FROM role_permissions WHERE permission_id = (SELECT id FROM permissions WHERE code = 'customers:assign_staff');
--rollback DELETE FROM permissions WHERE code = 'customers:assign_staff';
