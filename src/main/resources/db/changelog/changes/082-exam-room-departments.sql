--liquibase formatted sql

--changeset vitalcare:082-exam-room-departments splitStatements:false
--comment Exam rooms and visits belong to a department, and every clinic is laid out as two departments of three rooms: P01-P03 Tim mạch, P04-P06 Hô hấp. Missing rooms are created; any other room is retired unless somebody is in it now. Each department's doctors sit in its rooms, one doctor to a room for their whole week (demo doctors first, test accounts after); a doctor left over has no room until one is given in the doctor's schedule.

ALTER TABLE exam_room ADD COLUMN department varchar(64);
-- The department a visit is for: the front desk asks it at walk-in, and from the shared line only that
-- department's rooms call the patient in. Null (an online booking) can be called by any room.
ALTER TABLE appointment ADD COLUMN department varchar(64);

DO $rooms$
DECLARE
    c       record;
    d       record;
    n       integer;
    room_id bigint;
BEGIN
    FOR c IN SELECT clinic_id FROM clinic WHERE deleted_at IS NULL LOOP
        -- The six rooms, created when missing, named after their department.
        FOR n IN 1 .. 6 LOOP
            SELECT id INTO room_id FROM exam_room
            WHERE clinic_id = c.clinic_id AND deleted_at IS NULL AND lower(code) = 'p' || lpad(n::text, 2, '0');
            IF room_id IS NULL THEN
                INSERT INTO exam_room (clinic_id, code, status, created_at, version)
                VALUES (c.clinic_id, 'P' || lpad(n::text, 2, '0'), 'AVAILABLE', now(), 0)
                RETURNING id INTO room_id;
            END IF;
            UPDATE exam_room
            SET department = CASE WHEN n <= 3 THEN 'Tim mạch' ELSE 'Hô hấp' END,
                name = CASE WHEN n <= 3 THEN 'Phòng Tim mạch ' || n ELSE 'Phòng Hô hấp ' || (n - 3) END,
                updated_at = now()
            WHERE id = room_id;
        END LOOP;

        -- Every other room goes, unless a patient is inside it right now.
        UPDATE doctor_working_hours w SET exam_room_id = NULL
        FROM exam_room r
        WHERE w.exam_room_id = r.id AND r.clinic_id = c.clinic_id AND r.department IS NULL
          AND r.current_appointment_id IS NULL;
        UPDATE exam_room SET deleted_at = now()
        WHERE clinic_id = c.clinic_id AND deleted_at IS NULL AND department IS NULL
          AND current_appointment_id IS NULL;

        -- The clinic's doctors are seated afresh: none keeps a room, then each department's
        -- first three take its rooms in order.
        UPDATE doctor_working_hours w SET exam_room_id = NULL
        FROM employee e
        WHERE w.employee_id = e.id AND e.clinic_id = c.clinic_id AND w.exam_room_id IS NOT NULL;

        FOR d IN
            SELECT e.id, r.id AS room
            FROM (SELECT e.id, e.specialty,
                         row_number() OVER (PARTITION BY e.specialty
                                            ORDER BY (u.phone LIKE '+849110000%'), e.id) AS k
                  FROM employee e JOIN users u ON u.id = e.user_id
                  WHERE e.clinic_id = c.clinic_id AND e.staff_type = 'DOCTOR' AND e.status = 'ACTIVE'
                    AND e.deleted_at IS NULL AND e.specialty IN ('Tim mạch', 'Hô hấp')) e
            JOIN exam_room r ON r.clinic_id = c.clinic_id AND r.deleted_at IS NULL
                AND r.department = e.specialty
                AND lower(r.code) = 'p' || lpad((e.k + CASE WHEN e.specialty = 'Tim mạch' THEN 0 ELSE 3 END)::text, 2, '0')
            WHERE e.k <= 3
        LOOP
            UPDATE doctor_working_hours SET exam_room_id = d.room
            WHERE employee_id = d.id AND deleted_at IS NULL;
        END LOOP;
    END LOOP;
END
$rooms$;

--rollback ALTER TABLE appointment DROP COLUMN department;
--rollback ALTER TABLE exam_room DROP COLUMN department;
