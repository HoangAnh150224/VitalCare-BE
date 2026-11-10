--liquibase formatted sql

--changeset vitalcare:065-seed-test-accounts-per-role
--comment Test accounts for every role at both clinics, for the test team on any environment: a clinic administrator, a doctor (with a week of hours) and a front desk at each clinic, and a patient, a guest and a caregiver. All sign in with VitalCareTest#2026 (the hash 050 uses). Numbers 0911000001-09 are kept for this; each is skipped when already taken, so nobody's account is overwritten.

-- Clinic 1 = VitalCare Clinic, clinic 2 = the second clinic (whatever it is called now).
CREATE TEMP TABLE seed_staff (phone varchar(20), full_name varchar(255), role_code varchar(64), staff_type varchar(64),
                              clinic_id uuid, specialty varchar(255), title varchar(255)) ON COMMIT DROP;
INSERT INTO seed_staff VALUES
  ('+84911000001', 'QTPK. Test Cơ sở 1', 'CLINIC_ADMIN', 'CLINIC_ADMIN', '5b1f8c2e-6a3d-4f7e-9c41-2d8e0a7b3c19', NULL, 'Quản trị phòng khám'),
  ('+84911000002', 'QTPK. Test Cơ sở 2', 'CLINIC_ADMIN', 'CLINIC_ADMIN', 'c2a7e4d1-3b8f-4c62-9a15-7e0d3f9b2a48', NULL, 'Quản trị phòng khám'),
  ('+84911000003', 'BS. Test Cơ sở 1',   'DOCTOR',       'DOCTOR',       '5b1f8c2e-6a3d-4f7e-9c41-2d8e0a7b3c19', 'Nội tổng quát', 'Bác sĩ'),
  ('+84911000004', 'BS. Test Cơ sở 2',   'DOCTOR',       'DOCTOR',       'c2a7e4d1-3b8f-4c62-9a15-7e0d3f9b2a48', 'Nội tổng quát', 'Bác sĩ'),
  ('+84911000005', 'LT. Test Cơ sở 1',   'MANAGER',      'RECEPTIONIST', '5b1f8c2e-6a3d-4f7e-9c41-2d8e0a7b3c19', NULL, 'Lễ tân'),
  ('+84911000006', 'LT. Test Cơ sở 2',   'MANAGER',      'RECEPTIONIST', 'c2a7e4d1-3b8f-4c62-9a15-7e0d3f9b2a48', NULL, 'Lễ tân');

-- Only the numbers still free, at clinics that exist here.
DELETE FROM seed_staff s
WHERE EXISTS (SELECT 1 FROM users u WHERE u.phone = s.phone)
   OR NOT EXISTS (SELECT 1 FROM clinic c WHERE c.clinic_id = s.clinic_id);

INSERT INTO users (phone, password_hash, full_name, status, created_at)
SELECT phone, '$2a$10$IthJ0J/8bj.DX595lw6Doewym8FL6aUGRfXLHLWNrZ7QdIeLtk1Eu', full_name, 'ACTIVE', now() FROM seed_staff;

INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id FROM seed_staff s JOIN users u ON u.phone = s.phone JOIN roles r ON r.code = s.role_code;

INSERT INTO employee (user_id, clinic_id, staff_type, specialty, professional_title, clinic_position, status, created_at, version)
SELECT u.id, s.clinic_id, s.staff_type, s.specialty, s.title, s.title, 'ACTIVE', now(), 0
FROM seed_staff s JOIN users u ON u.phone = s.phone;

UPDATE employee e SET employee_code = 'NV' || lpad(e.id::text, 6, '0')
FROM seed_staff s JOIN users u ON u.phone = s.phone
WHERE e.user_id = u.id AND e.employee_code IS NULL;

INSERT INTO staff_membership (employee_id, clinic_id, status, started_at, created_at)
SELECT e.id, e.clinic_id, 'ACTIVE', now(), now()
FROM seed_staff s JOIN users u ON u.phone = s.phone JOIN employee e ON e.user_id = u.id;

-- The doctors see patients Monday to Saturday, the clinic's hours (as 057), so their clinics take bookings.
INSERT INTO doctor_working_hours (employee_id, day_of_week, start_time, end_time, visit_minutes, created_at, version)
SELECT e.id, day.n, session.start_time, session.end_time, 30, now(), 0
FROM seed_staff s JOIN users u ON u.phone = s.phone JOIN employee e ON e.user_id = u.id
CROSS JOIN generate_series(1, 6) AS day(n)
CROSS JOIN (VALUES (time '07:30', time '11:30'), (time '13:30', time '16:30')) AS session(start_time, end_time)
WHERE s.staff_type = 'DOCTOR';

-- A patient (seen before, so already a Patient), a guest (registered, not yet seen) and a caregiver
-- (a guest the patient shares vitals, alerts and visits with).
CREATE TEMP TABLE seed_customer (phone varchar(20), full_name varchar(255), role_code varchar(64), status varchar(32),
                                 date_of_birth date, gender varchar(16)) ON COMMIT DROP;
INSERT INTO seed_customer VALUES
  ('+84911000007', 'Bệnh nhân Test',     'PATIENT', 'PATIENT', DATE '1990-05-20', 'male'),
  ('+84911000008', 'Khách Test',         'GUEST',   'NEUTRAL', DATE '1995-08-12', 'female'),
  ('+84911000009', 'Người chăm sóc Test', 'GUEST',   'NEUTRAL', DATE '1988-02-03', 'female');
DELETE FROM seed_customer s WHERE EXISTS (SELECT 1 FROM users u WHERE u.phone = s.phone);

INSERT INTO users (phone, password_hash, full_name, status, created_at)
SELECT phone, '$2a$10$IthJ0J/8bj.DX595lw6Doewym8FL6aUGRfXLHLWNrZ7QdIeLtk1Eu', full_name, 'ACTIVE', now() FROM seed_customer;

INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id FROM seed_customer s JOIN users u ON u.phone = s.phone JOIN roles r ON r.code = s.role_code;

-- A whole profile (birth date, gender, address), so booking and editing work straight away.
INSERT INTO customer (user_id, date_of_birth, gender, province, ward, address, nationality, status,
                      patient_activated_at, patient_activation_source, created_at, version)
SELECT u.id, s.date_of_birth, s.gender, 'Hà Nội', 'Phường Cầu Giấy', '1 Phố Test', 'Việt Nam', s.status,
       CASE WHEN s.status = 'PATIENT' THEN now() END, CASE WHEN s.status = 'PATIENT' THEN 'CHECK_IN' END, now(), 0
FROM seed_customer s JOIN users u ON u.phone = s.phone;

UPDATE customer c SET customer_code = 'KH' || lpad(c.id::text, 6, '0')
FROM seed_customer s JOIN users u ON u.phone = s.phone
WHERE c.user_id = u.id AND c.customer_code IS NULL;

INSERT INTO sharing_permission (customer_id, caregiver_user_id, share_vitals, share_alerts, share_appointments,
                                emergency_phone, note, status, created_at, version)
SELECT p.id, cg.id, TRUE, TRUE, TRUE, '+84911000009', 'Tài khoản test của nhóm kiểm thử', 'ACTIVE', now(), 0
FROM customer p JOIN users pu ON pu.id = p.user_id AND pu.phone = '+84911000007'
JOIN users cg ON cg.phone = '+84911000009'
WHERE EXISTS (SELECT 1 FROM seed_customer s WHERE s.phone = '+84911000009');

--rollback -- Left in place: the test team may have data under these accounts by then.
