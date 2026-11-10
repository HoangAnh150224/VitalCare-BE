--liquibase formatted sql

--changeset vitalcare:061-drop-nurse
--comment Healthcare staff is the front desk only; there are no nurses. A nurse becomes front-desk staff (their account and clinic stay), leaves the care teams they were on - only doctors follow patients now - and the NURSE role goes, with its grants and row-level policies.

UPDATE monitoring_assignment m
SET status = 'ENDED', unassigned_at = now()
FROM employee e
WHERE e.id = m.employee_id
  AND e.staff_type = 'NURSE'
  AND m.unassigned_at IS NULL
  AND m.deleted_at IS NULL;

UPDATE employee SET staff_type = 'RECEPTIONIST' WHERE staff_type = 'NURSE';

-- Every account that was a nurse keeps signing in as front desk.
INSERT INTO user_roles (user_id, role_id)
SELECT ur.user_id, manager.id
FROM user_roles ur
JOIN roles nurse ON nurse.id = ur.role_id AND nurse.code = 'NURSE'
JOIN roles manager ON manager.code = 'MANAGER'
WHERE EXISTS (SELECT 1 FROM employee e WHERE e.user_id = ur.user_id AND e.staff_type = 'RECEPTIONIST')
  AND NOT EXISTS (SELECT 1 FROM user_roles x WHERE x.user_id = ur.user_id AND x.role_id = manager.id);

DELETE FROM user_roles WHERE role_id = (SELECT id FROM roles WHERE code = 'NURSE');
DELETE FROM roles WHERE code = 'NURSE';

--rollback -- Not restored: who was a nurse is not kept apart from the front desk once merged.
