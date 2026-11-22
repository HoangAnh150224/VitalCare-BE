--liquibase formatted sql

--changeset vitalcare:153-kan-153-front-desk-assigns-care-team
--comment The front desk (MANAGER) puts staff on a patient's care team and takes them off. 022 gave customers:assign_staff to the clinic administrator alone, but the care team is managed on the customer page, which is the front desk's screen and closed to the clinic administrator, so nobody could create a monitoring assignment in the UI. The clinic administrator keeps the grant; the service still limits both to patients and staff of their own clinic.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.code = 'MANAGER' AND p.code = 'customers:assign_staff'
AND NOT EXISTS (SELECT 1 FROM role_permissions rp WHERE rp.role_id = r.id AND rp.permission_id = p.id);
--rollback DELETE FROM role_permissions WHERE role_id IN (SELECT id FROM roles WHERE code = 'MANAGER') AND permission_id IN (SELECT id FROM permissions WHERE code = 'customers:assign_staff');
