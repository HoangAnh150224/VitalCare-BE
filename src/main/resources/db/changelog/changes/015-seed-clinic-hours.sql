--liquibase formatted sql

--changeset vitalcare:015-seed-clinic-hours
--comment Default opening hours for the demo clinic, and the permissions to read and change them.

-- Monday to Saturday, a morning and an afternoon session, half-hour slots of
-- three people each. Sunday has no row, which is what "closed" means.
INSERT INTO clinic_working_hours
  (clinic_id, day_of_week, open_time, close_time, slot_minutes, capacity_per_slot, created_at, version)
SELECT '5b1f8c2e-6a3d-4f7e-9c41-2d8e0a7b3c19', d.day, s.open_time, s.close_time, 30, 3,
       TIMESTAMP WITH TIME ZONE '2024-01-01 00:00:00+00', 0
FROM generate_series(1, 6) AS d(day)
CROSS JOIN (VALUES (TIME '07:30', TIME '11:30'),
                   (TIME '13:30', TIME '16:30')) AS s(open_time, close_time);

-- Reading a clinic's hours is open to anyone signed in, through the booking
-- screens; clinics:read is what puts the clinic screen in a staff menu.
-- Changing the hours changes what every customer can book, so it is ADMIN's.
INSERT INTO permissions (code, name, description, system_permission) VALUES
  ('clinics:read',  'View clinics',        'Open clinics and their opening hours',          TRUE),
  ('clinics:write', 'Edit clinic hours',   'Change the opening hours and slots of a clinic', TRUE);

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN ('clinics:read', 'clinics:write')
WHERE r.code = 'ADMIN';

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code = 'clinics:read'
WHERE r.code = 'MANAGER';

--rollback DELETE FROM role_permissions WHERE permission_id IN (SELECT id FROM permissions WHERE code LIKE 'clinics:%');
--rollback DELETE FROM permissions WHERE code LIKE 'clinics:%';
--rollback DELETE FROM clinic_working_hours WHERE clinic_id = '5b1f8c2e-6a3d-4f7e-9c41-2d8e0a7b3c19';
