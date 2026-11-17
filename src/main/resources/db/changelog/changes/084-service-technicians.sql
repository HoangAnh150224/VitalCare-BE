--liquibase formatted sql

--changeset vitalcare:084-service-technicians splitStatements:false
--comment The examining doctor orders services; the clinic's service technicians (Bác sĩ kỹ thuật) carry them out and record their results. A new staff kind and role TECHNICIAN; a new permission service_orders:perform (start, record the result, attach files) held only by it, while doctors keep ordering, reviewing and cancelling. Technicians work their clinic's orders only. One test technician per test clinic, with the test password 065 uses; each is skipped when the number is taken.

INSERT INTO roles (code, name, description, system_role)
SELECT 'TECHNICIAN', 'Technician', 'Service technician: carries out ordered services and records their results.', TRUE
WHERE NOT EXISTS (SELECT 1 FROM roles WHERE code = 'TECHNICIAN');

INSERT INTO permissions (code, name, description, system_permission)
SELECT 'service_orders:perform', 'Perform service orders', 'Start an ordered service, record its result, attach files', TRUE
WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE code = 'service_orders:perform');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN ('service_orders:read', 'service_orders:perform', 'medical_services:read',
                                 'notifications:read', 'notifications:write')
WHERE r.code = 'TECHNICIAN'
  AND NOT EXISTS (SELECT 1 FROM role_permissions x WHERE x.role_id = r.id AND x.permission_id = p.id);

-- Their clinic's orders, read and worked (the row-level side of performing them).
INSERT INTO row_level_policy (kind, role_id, resource, action, name, policy_group, description, scope, enabled, created_at)
SELECT 'SCOPE', r.id, 'service_orders', x.action, 'Cơ sở của mình', 'clinic',
       'Dữ liệu tại cơ sở của mình.',
       '{"field": "clinicId", "op": "eq", "value": {"ctx": "user.clinicId"}}'::jsonb, TRUE, now()
FROM roles r
CROSS JOIN (VALUES ('read'), ('write')) AS x(action)
WHERE r.code = 'TECHNICIAN'
  AND NOT EXISTS (SELECT 1 FROM row_level_policy p
                  WHERE p.role_id = r.id AND p.resource = 'service_orders' AND p.action = x.action);

-- A test technician at each test clinic (clinic 1 = VitalCare Clinic, clinic 2 = the second clinic).
CREATE TEMP TABLE seed_technician (phone varchar(20), full_name varchar(255), clinic_id uuid) ON COMMIT DROP;
INSERT INTO seed_technician VALUES
  ('+84913000001', 'BS. Kỹ thuật Test Cơ sở 1', '5b1f8c2e-6a3d-4f7e-9c41-2d8e0a7b3c19'),
  ('+84913000002', 'BS. Kỹ thuật Test Cơ sở 2', 'c2a7e4d1-3b8f-4c62-9a15-7e0d3f9b2a48');

DELETE FROM seed_technician s
WHERE EXISTS (SELECT 1 FROM users u WHERE u.phone = s.phone)
   OR NOT EXISTS (SELECT 1 FROM clinic c WHERE c.clinic_id = s.clinic_id);

INSERT INTO users (phone, password_hash, full_name, status, created_at)
SELECT phone, '$2a$10$IthJ0J/8bj.DX595lw6Doewym8FL6aUGRfXLHLWNrZ7QdIeLtk1Eu', full_name, 'ACTIVE', now()
FROM seed_technician;

INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id FROM seed_technician s JOIN users u ON u.phone = s.phone JOIN roles r ON r.code = 'TECHNICIAN';

INSERT INTO employee (user_id, clinic_id, staff_type, specialty, professional_title, clinic_position, status, created_at, version)
SELECT u.id, s.clinic_id, 'TECHNICIAN', 'Cận lâm sàng', 'Bác sĩ kỹ thuật', 'Bác sĩ kỹ thuật', 'ACTIVE', now(), 0
FROM seed_technician s JOIN users u ON u.phone = s.phone;

UPDATE employee e SET employee_code = 'NV' || lpad(e.id::text, 6, '0')
FROM seed_technician s JOIN users u ON u.phone = s.phone
WHERE e.user_id = u.id AND e.employee_code IS NULL;

INSERT INTO staff_membership (employee_id, clinic_id, status, started_at, created_at)
SELECT e.id, e.clinic_id, 'ACTIVE', now(), now()
FROM seed_technician s JOIN users u ON u.phone = s.phone JOIN employee e ON e.user_id = u.id;

--rollback DELETE FROM row_level_policy WHERE role_id IN (SELECT id FROM roles WHERE code = 'TECHNICIAN');
--rollback DELETE FROM role_permissions WHERE role_id IN (SELECT id FROM roles WHERE code = 'TECHNICIAN') OR permission_id IN (SELECT id FROM permissions WHERE code = 'service_orders:perform');
--rollback DELETE FROM permissions WHERE code = 'service_orders:perform';
