--liquibase formatted sql

--changeset vitalcare:017-seed-staff-devices
--comment Doctor and nurse roles, staff and device permissions, two demo staff and three demo devices.

-- What a member of clinical staff's account holds. System roles: creating a
-- member of staff assigns them by code.
INSERT INTO roles (code, name, description, system_role) VALUES
  ('DOCTOR', 'Doctor', 'Clinical staff: follows the patients assigned to them.', TRUE),
  ('NURSE',  'Nurse',  'Clinical staff: follows the patients assigned to them.', TRUE);

-- customers:assign covers both the care team and the device: putting a
-- patient in somebody's care and on a device are the same desk's job.
-- my_patients is the clinical side: the patients assigned to the caller, and
-- nobody else's.
INSERT INTO permissions (code, name, description, system_permission) VALUES
  ('employees:read',    'View staff',              'List and open members of staff',                         TRUE),
  ('employees:write',   'Manage staff',            'Create staff accounts, edit and deactivate staff',       TRUE),
  ('devices:read',      'View devices',            'List and open monitoring devices and their history',     TRUE),
  ('devices:write',     'Manage devices',          'Register devices and change their status',               TRUE),
  ('customers:assign',  'Assign care',             'Assign staff and devices to a patient, and end them',     TRUE),
  ('my_patients:read',  'View own patients',       'See the patients one is assigned to follow',             TRUE);

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN ('employees:read', 'employees:write', 'devices:read',
                                 'devices:write', 'customers:assign')
WHERE r.code = 'ADMIN';

-- The front desk assigns care and looks after devices; creating staff
-- accounts is an administrator's.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN ('employees:read', 'devices:read', 'devices:write', 'customers:assign')
WHERE r.code = 'MANAGER';

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code = 'my_patients:read'
WHERE r.code IN ('DOCTOR', 'NURSE');

-- Two members of staff to assign. Development credentials, like 002's: the
-- password for both is Staff@123, and the numbers are unassignable
-- placeholders.
INSERT INTO users (phone, email, password_hash, full_name, status, created_at) VALUES
  ('+84900000011', NULL, '$2a$10$/Djqk9GYDFrRJaoEWjYPnufgE30PtNocsc64ZErWspUiye6arwnF6', 'BS. Nguyễn Minh Khoa', 'ACTIVE', TIMESTAMP WITH TIME ZONE '2024-01-01 00:00:00+00'),
  ('+84900000012', NULL, '$2a$10$/Djqk9GYDFrRJaoEWjYPnufgE30PtNocsc64ZErWspUiye6arwnF6', 'ĐD. Trần Thu Hà',      'ACTIVE', TIMESTAMP WITH TIME ZONE '2024-01-01 00:00:00+00');

INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id
FROM (VALUES ('+84900000011', 'DOCTOR'), ('+84900000012', 'NURSE')) AS v(phone, role_code)
JOIN users u ON u.phone = v.phone
JOIN roles r ON r.code = v.role_code;

INSERT INTO employee (user_id, clinic_id, employee_code, staff_type, specialty, professional_title, license_no, status, created_at, version)
SELECT u.id, '5b1f8c2e-6a3d-4f7e-9c41-2d8e0a7b3c19', v.code, v.staff_type, v.specialty, v.title, v.license,
       'ACTIVE', TIMESTAMP WITH TIME ZONE '2024-01-01 00:00:00+00', 0
FROM (VALUES ('+84900000011', 'NV-DEMO-01', 'DOCTOR', 'Nội tim mạch', 'Bác sĩ', 'CCHN-000001'),
             ('+84900000012', 'NV-DEMO-02', 'NURSE',  'Điều dưỡng',   'Điều dưỡng', 'CCHN-000002'))
     AS v(phone, code, staff_type, specialty, title, license)
JOIN users u ON u.phone = v.phone;

-- Three wristbands that measure all three vital signs, ready to hand out.
INSERT INTO medical_device (clinic_id, device_code, serial_number, device_type, manufacturer, model, status, registered_at, created_at, version) VALUES
  ('5b1f8c2e-6a3d-4f7e-9c41-2d8e0a7b3c19', 'VC-W-001', 'SN-2024-0001', 'WRIST_MONITOR', 'VitalCare', 'VC Band 1', 'AVAILABLE', TIMESTAMP WITH TIME ZONE '2024-01-01 00:00:00+00', TIMESTAMP WITH TIME ZONE '2024-01-01 00:00:00+00', 0),
  ('5b1f8c2e-6a3d-4f7e-9c41-2d8e0a7b3c19', 'VC-W-002', 'SN-2024-0002', 'WRIST_MONITOR', 'VitalCare', 'VC Band 1', 'AVAILABLE', TIMESTAMP WITH TIME ZONE '2024-01-01 00:00:00+00', TIMESTAMP WITH TIME ZONE '2024-01-01 00:00:00+00', 0),
  ('5b1f8c2e-6a3d-4f7e-9c41-2d8e0a7b3c19', 'VC-W-003', 'SN-2024-0003', 'WRIST_MONITOR', 'VitalCare', 'VC Band 1', 'AVAILABLE', TIMESTAMP WITH TIME ZONE '2024-01-01 00:00:00+00', TIMESTAMP WITH TIME ZONE '2024-01-01 00:00:00+00', 0);

--rollback DELETE FROM medical_device WHERE device_code IN ('VC-W-001', 'VC-W-002', 'VC-W-003');
--rollback DELETE FROM employee WHERE employee_code IN ('NV-DEMO-01', 'NV-DEMO-02');
--rollback DELETE FROM user_roles WHERE user_id IN (SELECT id FROM users WHERE phone IN ('+84900000011', '+84900000012'));
--rollback DELETE FROM users WHERE phone IN ('+84900000011', '+84900000012');
--rollback DELETE FROM role_permissions WHERE permission_id IN (SELECT id FROM permissions WHERE code IN ('employees:read','employees:write','devices:read','devices:write','customers:assign','my_patients:read'));
--rollback DELETE FROM permissions WHERE code IN ('employees:read','employees:write','devices:read','devices:write','customers:assign','my_patients:read');
--rollback DELETE FROM roles WHERE code IN ('DOCTOR', 'NURSE');
