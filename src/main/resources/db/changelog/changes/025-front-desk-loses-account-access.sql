--liquibase formatted sql

--changeset vitalcare:025-front-desk-loses-account-access
--comment The front desk no longer reads login accounts, roles or permissions.

-- Left over from the framework's seed (002), where MANAGER was a back-office
-- role. Login accounts are not split by clinic, so users:read let the front
-- desk of one clinic open the accounts of every other clinic's staff by id.
-- Account administration is the System Administrator's (SRS 3.1.3).
DELETE FROM role_permissions
WHERE role_id = (SELECT id FROM roles WHERE code = 'MANAGER')
  AND permission_id IN (SELECT id FROM permissions WHERE code IN ('users:read', 'roles:read', 'permissions:read'));

--rollback INSERT INTO role_permissions (role_id, permission_id)
--rollback SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code IN ('users:read', 'roles:read', 'permissions:read')
--rollback WHERE r.code = 'MANAGER';
