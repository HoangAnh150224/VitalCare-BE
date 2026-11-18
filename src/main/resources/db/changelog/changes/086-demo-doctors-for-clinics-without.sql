--liquibase formatted sql

--changeset vitalcare:086-demo-doctors-for-clinics-without splitStatements:false
--comment Demo data: a clinic with no active doctor gives no turns, so nobody can register there on any day (BR-01). Each such clinic that is operating gets the layout 082 gave the others - rooms P01-P03 Tim mạch, P04-P06 Hô hấp - and one demo doctor per room, working Monday to Saturday in the clinic's hours. They sign in with the test password 065 uses, on numbers from +84912001001 up (each skipped when already taken). Clinics that already have a doctor are left alone.

DO $seed$
DECLARE
    c          record;
    n          integer;
    clinic_no  integer := 0;
    next_phone bigint := 912001000;
    phone_txt  text;
    spec       text;
    room_id    bigint;
    user_id_   bigint;
    doctor_id  bigint;
    names      text[] := ARRAY[
        'BS. Trịnh Minh Khôi', 'BS. Lương Thị Thu Hà', 'BS. Cao Văn Hùng',
        'BS. Mai Thị Phương', 'BS. Tạ Quang Vinh', 'BS. Lý Thị Kim Oanh',
        'BS. Đỗ Hữu Phước', 'BS. Võ Thị Bích Ngọc', 'BS. Hà Đức Trung',
        'BS. Châu Thị Diệu Linh', 'BS. Kiều Văn Tùng', 'BS. Thái Thị Mỹ Duyên'];
BEGIN
    FOR c IN
        SELECT cl.clinic_id
        FROM clinic cl
        WHERE cl.deleted_at IS NULL
          AND coalesce(cl.operating_status, 'OPERATING') <> 'INACTIVE'
          AND NOT EXISTS (SELECT 1 FROM employee e
                          WHERE e.clinic_id = cl.clinic_id AND e.staff_type = 'DOCTOR'
                            AND e.status = 'ACTIVE' AND e.deleted_at IS NULL)
        ORDER BY cl.created_at, cl.clinic_id
    LOOP
        FOR n IN 1 .. 6 LOOP
            spec := CASE WHEN n <= 3 THEN 'Tim mạch' ELSE 'Hô hấp' END;

            -- The room, as 082 lays a clinic out: created when missing, given its department when it has none.
            room_id := NULL;
            SELECT id INTO room_id FROM exam_room
            WHERE clinic_id = c.clinic_id AND deleted_at IS NULL AND lower(code) = 'p' || lpad(n::text, 2, '0');
            IF room_id IS NULL THEN
                INSERT INTO exam_room (clinic_id, code, status, created_at, version)
                VALUES (c.clinic_id, 'P' || lpad(n::text, 2, '0'), 'AVAILABLE', now(), 0)
                RETURNING id INTO room_id;
            END IF;
            UPDATE exam_room
            SET department = spec,
                name = CASE WHEN n <= 3 THEN 'Phòng Tim mạch ' || n ELSE 'Phòng Hô hấp ' || (n - 3) END,
                updated_at = now()
            WHERE id = room_id AND department IS NULL;

            -- The next reserved number nobody holds.
            LOOP
                next_phone := next_phone + 1;
                phone_txt := '+84' || next_phone;
                EXIT WHEN NOT EXISTS (SELECT 1 FROM users u WHERE u.phone = phone_txt);
            END LOOP;

            INSERT INTO users (phone, password_hash, full_name, status, created_at)
            VALUES (phone_txt, '$2a$10$IthJ0J/8bj.DX595lw6Doewym8FL6aUGRfXLHLWNrZ7QdIeLtk1Eu',
                    names[1 + (clinic_no * 6 + n - 1) % array_length(names, 1)], 'ACTIVE', now())
            RETURNING id INTO user_id_;

            INSERT INTO user_roles (user_id, role_id)
            SELECT user_id_, r.id FROM roles r WHERE r.code = 'DOCTOR';

            INSERT INTO employee (user_id, clinic_id, staff_type, specialty, professional_title, clinic_position,
                                  license_no, status, created_at, version)
            VALUES (user_id_, c.clinic_id, 'DOCTOR', spec, 'Bác sĩ',
                    CASE WHEN n IN (1, 4) THEN 'Trưởng khoa' ELSE 'Bác sĩ điều trị' END,
                    'DEMO-CCHN-' || user_id_, 'ACTIVE', now(), 0)
            RETURNING id INTO doctor_id;

            UPDATE employee SET employee_code = 'NV' || lpad(doctor_id::text, 6, '0')
            WHERE id = doctor_id AND employee_code IS NULL;

            INSERT INTO staff_membership (employee_id, clinic_id, status, started_at, created_at)
            VALUES (doctor_id, c.clinic_id, 'ACTIVE', now(), now());

            -- Monday to Saturday, the clinic's hours (as 065 and 075), in the room above.
            INSERT INTO doctor_working_hours (employee_id, day_of_week, start_time, end_time, visit_minutes,
                                              exam_room_id, created_at, version)
            SELECT doctor_id, day.d, session.start_time, session.end_time, 30, room_id, now(), 0
            FROM generate_series(1, 6) AS day(d)
            CROSS JOIN (VALUES (time '07:30', time '11:30'), (time '13:30', time '16:30')) AS session(start_time, end_time);
        END LOOP;
        clinic_no := clinic_no + 1;
    END LOOP;
END
$seed$;

--rollback -- Left in place: visits may have been booked with these doctors by then.
