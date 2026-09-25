--liquibase formatted sql

--changeset vitalcare:005-seed-row-level-permissions
--comment Permissions for the row_level_policies resource. No policy rows are seeded.

INSERT INTO permissions (code, name, description, system_permission) VALUES
  ('row_level_policies:read',   'View data scopes',   'See which rows each role may reach',  TRUE),
  ('row_level_policies:write',  'Edit data scopes',   'Narrow or widen a role''s data scope', TRUE),
  ('row_level_policies:delete', 'Delete data scopes', 'Remove a data scope policy',          TRUE);

-- Spelled out for ADMIN rather than joined: 002 granted ADMIN every permission
-- that existed *when it ran*, which is a join over rows that did not yet
-- exist. Nobody but ADMIN gets these -- editing a data scope is editing the
-- security model.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN ('row_level_policies:read',
                                 'row_level_policies:write',
                                 'row_level_policies:delete')
WHERE r.code = 'ADMIN';

-- No row_level_policy rows are inserted, deliberately. Each resource declares
-- its own DefaultScope in code (RowLevelPolicySet.defaultScope), so switching a
-- running resource on is a no-op with no seed data to keep in step with
-- role_permissions -- and nobody loses a row on the day it ships.

--rollback DELETE FROM role_permissions WHERE permission_id IN (SELECT id FROM permissions WHERE code LIKE 'row_level_policies:%');
--rollback DELETE FROM permissions WHERE code LIKE 'row_level_policies:%';
