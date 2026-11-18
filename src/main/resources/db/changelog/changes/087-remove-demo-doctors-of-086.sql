--liquibase formatted sql

--changeset vitalcare:087-remove-demo-doctors-of-086 splitStatements:false
--comment Takes back the demo doctors 086 added to the clinics that had none (recognised by 086's own marks: a number from +84912001001 up and the licence DEMO-CCHN-<user id>): their hours, membership, employee record and account. One somebody has meanwhile booked or worked with cannot be deleted and is retired instead, so it no longer takes visits. The rooms 086 laid out stay: they are the layout 082 gives every clinic.

DO $undo$
DECLARE
    d record;
BEGIN
    FOR d IN
        SELECT e.id AS employee_id, u.id AS user_id
        FROM employee e JOIN users u ON u.id = e.user_id
        WHERE e.staff_type = 'DOCTOR'
          AND u.phone LIKE '+84912001%'
          AND e.license_no = 'DEMO-CCHN-' || u.id
    LOOP
        BEGIN
            DELETE FROM doctor_working_hours WHERE employee_id = d.employee_id;
            DELETE FROM staff_membership WHERE employee_id = d.employee_id;
            DELETE FROM employee WHERE id = d.employee_id;
            DELETE FROM user_roles WHERE user_id = d.user_id;
            DELETE FROM users WHERE id = d.user_id;
        EXCEPTION WHEN foreign_key_violation THEN
            -- Already part of somebody's record: kept, but out of the clinic's days.
            UPDATE doctor_working_hours SET deleted_at = now() WHERE employee_id = d.employee_id AND deleted_at IS NULL;
            UPDATE employee SET status = 'INACTIVE', deleted_at = now() WHERE id = d.employee_id;
            UPDATE users SET status = 'INACTIVE' WHERE id = d.user_id;
        END;
    END LOOP;
END
$undo$;

--rollback -- Nothing to put back: 086 is not run again.
