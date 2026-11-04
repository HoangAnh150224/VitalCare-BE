--liquibase formatted sql

--changeset vitalcare:019-front-desk-clinics
--comment The front desk works at a clinic; a second clinic so the difference shows.

-- A receptionist is staff with a clinic, like a doctor: the clinic on their
-- staff record is what limits the appointments, customers, devices and staff
-- they see. An administrator has no staff record and sees every clinic.

-- A second clinic, open the same hours as the first.
INSERT INTO clinic (clinic_id, clinic_name, address, contact_phone, operating_status, created_at, version) VALUES
  ('c2a7e4d1-3b8f-4c62-9a15-7e0d3f9b2a48', 'VitalCare Clinic – Cơ sở 2', '2 Demo Street, Ha Noi', '+84900000020', 'OPERATING',
   TIMESTAMP WITH TIME ZONE '2024-01-01 00:00:00+00', 0);

INSERT INTO clinic_working_hours
  (clinic_id, day_of_week, open_time, close_time, slot_minutes, capacity_per_slot, created_at, version)
SELECT 'c2a7e4d1-3b8f-4c62-9a15-7e0d3f9b2a48', d.day, s.open_time, s.close_time, 30, 3,
       TIMESTAMP WITH TIME ZONE '2024-01-01 00:00:00+00', 0
FROM generate_series(1, 6) AS d(day)
CROSS JOIN (VALUES (TIME '07:30', TIME '11:30'),
                   (TIME '13:30', TIME '16:30')) AS s(open_time, close_time);

-- The seeded front-desk account (002's "manager") becomes the first clinic's
-- receptionist. Its name is only changed while it is still the seed's.
INSERT INTO employee (user_id, clinic_id, employee_code, staff_type, clinic_position, status, created_at, version)
SELECT u.id, '5b1f8c2e-6a3d-4f7e-9c41-2d8e0a7b3c19', 'NV-DEMO-00', 'RECEPTIONIST', 'Lễ tân',
       'ACTIVE', TIMESTAMP WITH TIME ZONE '2024-01-01 00:00:00+00', 0
FROM users u
WHERE u.phone = '+84900000002';

UPDATE users SET full_name = 'LT. Nguyễn Thị Mai'
WHERE phone = '+84900000002' AND full_name = 'Content Manager';

-- The second clinic's front desk and a doctor of its own. Development
-- credentials, like 017's: the password for both is Staff@123.
INSERT INTO users (phone, email, password_hash, full_name, status, created_at) VALUES
  ('+84900000021', NULL, '$2a$10$/Djqk9GYDFrRJaoEWjYPnufgE30PtNocsc64ZErWspUiye6arwnF6', 'LT. Phạm Ngọc Lan', 'ACTIVE', TIMESTAMP WITH TIME ZONE '2024-01-01 00:00:00+00'),
  ('+84900000022', NULL, '$2a$10$/Djqk9GYDFrRJaoEWjYPnufgE30PtNocsc64ZErWspUiye6arwnF6', 'BS. Đỗ Quang Huy',  'ACTIVE', TIMESTAMP WITH TIME ZONE '2024-01-01 00:00:00+00');

INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id
FROM (VALUES ('+84900000021', 'MANAGER'), ('+84900000022', 'DOCTOR')) AS v(phone, role_code)
JOIN users u ON u.phone = v.phone
JOIN roles r ON r.code = v.role_code;

INSERT INTO employee (user_id, clinic_id, employee_code, staff_type, specialty, professional_title, clinic_position, status, created_at, version)
SELECT u.id, 'c2a7e4d1-3b8f-4c62-9a15-7e0d3f9b2a48', v.code, v.staff_type, v.specialty, v.title, v.position,
       'ACTIVE', TIMESTAMP WITH TIME ZONE '2024-01-01 00:00:00+00', 0
FROM (VALUES ('+84900000021', 'NV-DEMO-03', 'RECEPTIONIST', NULL,        NULL,     'Lễ tân'),
             ('+84900000022', 'NV-DEMO-04', 'DOCTOR',       'Nội tổng quát', 'Bác sĩ', NULL))
     AS v(phone, code, staff_type, specialty, title, position)
JOIN users u ON u.phone = v.phone;

INSERT INTO medical_device (clinic_id, device_code, serial_number, device_type, manufacturer, model, status, registered_at, created_at, version) VALUES
  ('c2a7e4d1-3b8f-4c62-9a15-7e0d3f9b2a48', 'VC-W-101', 'SN-2024-0101', 'WRIST_MONITOR', 'VitalCare', 'VC Band 1', 'AVAILABLE', TIMESTAMP WITH TIME ZONE '2024-01-01 00:00:00+00', TIMESTAMP WITH TIME ZONE '2024-01-01 00:00:00+00', 0),
  ('c2a7e4d1-3b8f-4c62-9a15-7e0d3f9b2a48', 'VC-W-102', 'SN-2024-0102', 'WRIST_MONITOR', 'VitalCare', 'VC Band 1', 'AVAILABLE', TIMESTAMP WITH TIME ZONE '2024-01-01 00:00:00+00', TIMESTAMP WITH TIME ZONE '2024-01-01 00:00:00+00', 0);

--rollback DELETE FROM medical_device WHERE device_code IN ('VC-W-101', 'VC-W-102');
--rollback DELETE FROM employee WHERE employee_code IN ('NV-DEMO-00', 'NV-DEMO-03', 'NV-DEMO-04');
--rollback DELETE FROM user_roles WHERE user_id IN (SELECT id FROM users WHERE phone IN ('+84900000021', '+84900000022'));
--rollback DELETE FROM users WHERE phone IN ('+84900000021', '+84900000022');
--rollback DELETE FROM clinic_working_hours WHERE clinic_id = 'c2a7e4d1-3b8f-4c62-9a15-7e0d3f9b2a48';
--rollback DELETE FROM clinic WHERE clinic_id = 'c2a7e4d1-3b8f-4c62-9a15-7e0d3f9b2a48';
