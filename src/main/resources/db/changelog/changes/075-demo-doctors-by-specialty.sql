--liquibase formatted sql

--changeset vitalcare:075-demo-doctors-by-specialty
--comment Demo data: three doctors each for Tim mạch and Hô hấp at both clinics, with a week of hours so they take bookings. All sign in with the test password 065 uses. Numbers 0912000001-12 are kept for this; each is skipped when already taken, so nobody's account is overwritten.

CREATE TEMP TABLE seed_doctor (phone varchar(20), full_name varchar(255), clinic_id uuid, specialty varchar(255),
                               title varchar(255), position varchar(128), license_no varchar(128)) ON COMMIT DROP;
INSERT INTO seed_doctor VALUES
  -- Cơ sở 1
  ('+84912000001', 'BS. Lê Hoàng Nam',      '5b1f8c2e-6a3d-4f7e-9c41-2d8e0a7b3c19', 'Tim mạch', 'Tiến sĩ, Bác sĩ',       'Trưởng khoa',     'DEMO-CCHN-TM-101'),
  ('+84912000002', 'BS. Phạm Thị Thanh Vân', '5b1f8c2e-6a3d-4f7e-9c41-2d8e0a7b3c19', 'Tim mạch', 'Bác sĩ chuyên khoa II', 'Phó trưởng khoa', 'DEMO-CCHN-TM-102'),
  ('+84912000003', 'BS. Vũ Đức Anh',        '5b1f8c2e-6a3d-4f7e-9c41-2d8e0a7b3c19', 'Tim mạch', 'Bác sĩ chuyên khoa I',  'Bác sĩ điều trị', 'DEMO-CCHN-TM-103'),
  ('+84912000004', 'BS. Trần Quốc Bảo',     '5b1f8c2e-6a3d-4f7e-9c41-2d8e0a7b3c19', 'Hô hấp',   'Thạc sĩ, Bác sĩ',       'Trưởng khoa',     'DEMO-CCHN-HH-101'),
  ('+84912000005', 'BS. Nguyễn Thị Hồng',   '5b1f8c2e-6a3d-4f7e-9c41-2d8e0a7b3c19', 'Hô hấp',   'Bác sĩ chuyên khoa I',  'Bác sĩ điều trị', 'DEMO-CCHN-HH-102'),
  ('+84912000006', 'BS. Đặng Minh Tuấn',    '5b1f8c2e-6a3d-4f7e-9c41-2d8e0a7b3c19', 'Hô hấp',   'Bác sĩ',                'Bác sĩ điều trị', 'DEMO-CCHN-HH-103'),
  -- Cơ sở 2
  ('+84912000007', 'BS. Hoàng Văn Sơn',     'c2a7e4d1-3b8f-4c62-9a15-7e0d3f9b2a48', 'Tim mạch', 'Bác sĩ chuyên khoa II', 'Trưởng khoa',     'DEMO-CCHN-TM-201'),
  ('+84912000008', 'BS. Bùi Thị Ngọc Ánh',  'c2a7e4d1-3b8f-4c62-9a15-7e0d3f9b2a48', 'Tim mạch', 'Thạc sĩ, Bác sĩ',       'Bác sĩ điều trị', 'DEMO-CCHN-TM-202'),
  ('+84912000009', 'BS. Đinh Công Thành',   'c2a7e4d1-3b8f-4c62-9a15-7e0d3f9b2a48', 'Tim mạch', 'Bác sĩ',                'Bác sĩ điều trị', 'DEMO-CCHN-TM-203'),
  ('+84912000010', 'BS. Ngô Thị Lan Hương', 'c2a7e4d1-3b8f-4c62-9a15-7e0d3f9b2a48', 'Hô hấp',   'Tiến sĩ, Bác sĩ',       'Trưởng khoa',     'DEMO-CCHN-HH-201'),
  ('+84912000011', 'BS. Phan Văn Long',     'c2a7e4d1-3b8f-4c62-9a15-7e0d3f9b2a48', 'Hô hấp',   'Bác sĩ chuyên khoa I',  'Phó trưởng khoa', 'DEMO-CCHN-HH-202'),
  ('+84912000012', 'BS. Dương Thị Mai Anh', 'c2a7e4d1-3b8f-4c62-9a15-7e0d3f9b2a48', 'Hô hấp',   'Bác sĩ',                'Bác sĩ điều trị', 'DEMO-CCHN-HH-203');

-- Only the numbers still free, at clinics that exist here.
DELETE FROM seed_doctor s
WHERE EXISTS (SELECT 1 FROM users u WHERE u.phone = s.phone)
   OR NOT EXISTS (SELECT 1 FROM clinic c WHERE c.clinic_id = s.clinic_id);

INSERT INTO users (phone, password_hash, full_name, status, created_at)
SELECT phone, '$2a$10$IthJ0J/8bj.DX595lw6Doewym8FL6aUGRfXLHLWNrZ7QdIeLtk1Eu', full_name, 'ACTIVE', now() FROM seed_doctor;

INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id FROM seed_doctor s JOIN users u ON u.phone = s.phone JOIN roles r ON r.code = 'DOCTOR';

INSERT INTO employee (user_id, clinic_id, staff_type, specialty, professional_title, clinic_position, license_no, status, created_at, version)
SELECT u.id, s.clinic_id, 'DOCTOR', s.specialty, s.title, s.position, s.license_no, 'ACTIVE', now(), 0
FROM seed_doctor s JOIN users u ON u.phone = s.phone;

UPDATE employee e SET employee_code = 'NV' || lpad(e.id::text, 6, '0')
FROM seed_doctor s JOIN users u ON u.phone = s.phone
WHERE e.user_id = u.id AND e.employee_code IS NULL;

INSERT INTO staff_membership (employee_id, clinic_id, status, started_at, created_at)
SELECT e.id, e.clinic_id, 'ACTIVE', now(), now()
FROM seed_doctor s JOIN users u ON u.phone = s.phone JOIN employee e ON e.user_id = u.id;

-- Monday to Saturday, the clinic's hours (as 065).
INSERT INTO doctor_working_hours (employee_id, day_of_week, start_time, end_time, visit_minutes, created_at, version)
SELECT e.id, day.n, session.start_time, session.end_time, 30, now(), 0
FROM seed_doctor s JOIN users u ON u.phone = s.phone JOIN employee e ON e.user_id = u.id
CROSS JOIN generate_series(1, 6) AS day(n)
CROSS JOIN (VALUES (time '07:30', time '11:30'), (time '13:30', time '16:30')) AS session(start_time, end_time);

--rollback -- Left in place: visits may have been booked with these doctors by then.

--changeset vitalcare:075-demo-doctors-exam-rooms splitStatements:false
--comment As 069, for the doctors above: each one whose sessions have no exam room gets a room of their own at their clinic - a room no doctor uses, else a new one with the first code free.

DO $$
DECLARE
    d       record;
    room_id bigint;
    n       integer;
BEGIN
    FOR d IN
        SELECT e.id, e.clinic_id
        FROM employee e
        WHERE e.staff_type = 'DOCTOR' AND e.status = 'ACTIVE' AND e.deleted_at IS NULL AND e.clinic_id IS NOT NULL
          AND EXISTS (SELECT 1 FROM doctor_working_hours w
                      WHERE w.employee_id = e.id AND w.exam_room_id IS NULL AND w.deleted_at IS NULL)
        ORDER BY e.clinic_id, e.id
    LOOP
        room_id := NULL;
        SELECT r.id INTO room_id
        FROM exam_room r
        WHERE r.clinic_id = d.clinic_id AND r.deleted_at IS NULL
          AND NOT EXISTS (SELECT 1 FROM doctor_working_hours w WHERE w.exam_room_id = r.id AND w.deleted_at IS NULL)
        ORDER BY r.id LIMIT 1;

        IF room_id IS NULL THEN
            n := 1;
            WHILE EXISTS (SELECT 1 FROM exam_room r
                          WHERE r.clinic_id = d.clinic_id AND r.deleted_at IS NULL
                            AND lower(r.code) = 'p' || lpad(n::text, 2, '0')) LOOP
                n := n + 1;
            END LOOP;
            INSERT INTO exam_room (clinic_id, code, name, status, created_at, version)
            VALUES (d.clinic_id, 'P' || lpad(n::text, 2, '0'), 'Phòng khám ' || n, 'AVAILABLE', now(), 0)
            RETURNING id INTO room_id;
        END IF;

        UPDATE doctor_working_hours
        SET exam_room_id = room_id
        WHERE employee_id = d.id AND exam_room_id IS NULL AND deleted_at IS NULL;
    END LOOP;
END $$;

--rollback -- Left in place: visits may already have been called into these rooms.
