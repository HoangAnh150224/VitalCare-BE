--liquibase formatted sql

--changeset vitalcare:057-default-doctor-working-hours
--comment A doctor with no working hours gives the clinic no turns, so nobody can register there (BR-01). Every active doctor still without a week gets the clinic's opening hours, Monday to Saturday, 30-minute visits, in an exam room of their clinic when there is one free for them. Doctors who already have hours are left alone.

WITH doctors AS (
    SELECT e.id, e.clinic_id,
           row_number() OVER (PARTITION BY e.clinic_id ORDER BY e.id) AS n
    FROM employee e
    WHERE e.staff_type = 'DOCTOR'
      AND e.status = 'ACTIVE'
      AND e.deleted_at IS NULL
      AND NOT EXISTS (SELECT 1 FROM doctor_working_hours w WHERE w.employee_id = e.id AND w.deleted_at IS NULL)
),
rooms AS (
    SELECT r.id, r.clinic_id,
           row_number() OVER (PARTITION BY r.clinic_id ORDER BY r.id) AS n
    FROM exam_room r
    WHERE r.deleted_at IS NULL
      -- Rooms another doctor's week already uses stay theirs.
      AND NOT EXISTS (SELECT 1 FROM doctor_working_hours w WHERE w.exam_room_id = r.id AND w.deleted_at IS NULL)
)
INSERT INTO doctor_working_hours (employee_id, day_of_week, start_time, end_time, visit_minutes, exam_room_id, created_at, version)
SELECT d.id, day.n, session.start_time, session.end_time, 30, room.id, now(), 0
FROM doctors d
LEFT JOIN rooms room ON room.clinic_id = d.clinic_id AND room.n = d.n
CROSS JOIN generate_series(1, 6) AS day(n)
CROSS JOIN (VALUES (time '07:30', time '11:30'), (time '13:30', time '16:30')) AS session(start_time, end_time);

--rollback -- Left in place: by the time this is undone, appointments may already depend on these hours.
