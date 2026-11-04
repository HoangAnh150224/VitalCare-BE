--liquibase formatted sql

--changeset vitalcare:026-doctor-schedules-and-assignments
--comment Doctor schedules and the assignment list: permissions, grants and clinic scopes.

-- The entity now maps status as an enum (WORKING, LEAVE); nothing has written the table yet.
UPDATE doctor_schedule SET status = 'WORKING' WHERE status IS NULL OR status NOT IN ('WORKING', 'LEAVE');
ALTER TABLE doctor_schedule ALTER COLUMN status SET NOT NULL;

INSERT INTO permissions (code, name, description, system_permission) VALUES
  ('doctor_schedules:read',       'View doctor schedules',   'See when each doctor of the clinic works or is off', TRUE),
  ('doctor_schedules:write',      'Manage doctor schedules', 'Add and edit working periods and time off',          TRUE),
  ('doctor_schedules:delete',     'Delete doctor schedules', 'Remove a period from a doctor''s schedule',          TRUE),
  ('monitoring_assignments:read', 'View assignments',        'List and open the clinic''s care-team assignments',  TRUE);

-- SRS 3.1.3: Doctor, Healthcare Staff and the Clinic Administrator read both.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN ('doctor_schedules:read', 'monitoring_assignments:read')
WHERE r.code IN ('DOCTOR', 'NURSE', 'MANAGER', 'CLINIC_ADMIN');

-- Manage Doctor Availability: the Doctor (their own, which the service holds
-- them to) and the Clinic Administrator.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN ('doctor_schedules:write', 'doctor_schedules:delete')
WHERE r.code IN ('DOCTOR', 'CLINIC_ADMIN');

-- BR-04: a period belongs to the clinic it is worked at.
INSERT INTO row_level_policy (kind, role_id, resource, action, name, policy_group, description, scope, enabled, created_at)
SELECT 'SCOPE', r.id, 'doctor_schedules', a.action, 'Cơ sở của mình', 'clinic',
       'Lịch bác sĩ tại cơ sở của mình.',
       '{"field": "clinicId", "op": "eq", "value": {"ctx": "user.clinicId"}}'::jsonb, TRUE, now()
FROM roles r
CROSS JOIN (VALUES ('read'), ('write'), ('delete')) AS a(action)
WHERE (a.action = 'read' AND r.code IN ('DOCTOR', 'NURSE', 'MANAGER', 'CLINIC_ADMIN'))
   OR (a.action <> 'read' AND r.code IN ('DOCTOR', 'CLINIC_ADMIN'));

--rollback DELETE FROM row_level_policy WHERE resource = 'doctor_schedules';
--rollback DELETE FROM role_permissions WHERE permission_id IN (SELECT id FROM permissions WHERE code LIKE 'doctor_schedules:%' OR code = 'monitoring_assignments:read');
--rollback DELETE FROM permissions WHERE code LIKE 'doctor_schedules:%' OR code = 'monitoring_assignments:read';
--rollback ALTER TABLE doctor_schedule ALTER COLUMN status DROP NOT NULL;
