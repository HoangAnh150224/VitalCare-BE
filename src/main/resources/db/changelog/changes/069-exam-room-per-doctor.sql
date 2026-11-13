--liquibase formatted sql

--changeset vitalcare:069-exam-room-per-doctor splitStatements:false
--comment Test data for the shared queue with several rooms calling at once: every active doctor whose week still has sessions without an exam room gets a room of their own at their clinic - the one they already sit in, else a room no doctor uses, else a new one (P01, P02, ... the first code free at the clinic). Sessions that already have a room are left alone.

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
        SELECT r.id INTO room_id
        FROM doctor_working_hours w JOIN exam_room r ON r.id = w.exam_room_id AND r.deleted_at IS NULL
        WHERE w.employee_id = d.id AND w.deleted_at IS NULL AND r.clinic_id = d.clinic_id
        ORDER BY r.id LIMIT 1;

        IF room_id IS NULL THEN
            SELECT r.id INTO room_id
            FROM exam_room r
            WHERE r.clinic_id = d.clinic_id AND r.deleted_at IS NULL
              AND NOT EXISTS (SELECT 1 FROM doctor_working_hours w WHERE w.exam_room_id = r.id AND w.deleted_at IS NULL)
            ORDER BY r.id LIMIT 1;
        END IF;

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
