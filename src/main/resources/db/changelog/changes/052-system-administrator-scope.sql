--liquibase formatted sql

--changeset vitalcare:052-system-administrator-scope
--comment The System Administrator manages the system; clinic operations belong to clinic roles.

-- ADMIN originally received every permission, and later clinic features added
-- further grants for it. Keep only the system-wide screens proposed for this
-- role; the sidebar and API both consume this same permission set, so hiding a
-- menu also closes its direct URL and endpoint.
DELETE FROM role_permissions rp
USING roles r, permissions p
WHERE rp.role_id = r.id
  AND rp.permission_id = p.id
  AND r.code = 'ADMIN'
  AND split_part(p.code, ':', 1) NOT IN (
      'users', 'roles', 'clinics', 'audit_logs', 'permissions',
      'row_level_policies', 'notifications', 'staff_memberships'
  );

-- Policies attached to clinic resources no longer protect a resource ADMIN
-- can access. Remove those stale rows along with their grants.
DELETE FROM row_level_policy
WHERE role_id = (SELECT id FROM roles WHERE code = 'ADMIN')
  AND resource NOT IN (
      'users', 'roles', 'clinics', 'audit_logs', 'permissions',
      'row_level_policies', 'notifications', 'staff_memberships'
  );
