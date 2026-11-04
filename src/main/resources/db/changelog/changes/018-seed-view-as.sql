--liquibase formatted sql

--changeset vitalcare:018-seed-view-as
--comment Lets an administrator see a customer's or a member of staff's own screens, read-only.

-- Its own code rather than a side effect of ADMIN's other grants, so seeing
-- other people's personal screens can be taken away without touching anything
-- else an administrator does. The endpoints behind it are GET only: looking
-- through somebody's eyes never lets anybody act as them.
INSERT INTO permissions (code, name, description, system_permission) VALUES
  ('view_as:read', 'View as another role', 'See a customer''s or a member of staff''s own screens, read-only', TRUE);

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code = 'view_as:read'
WHERE r.code = 'ADMIN';

--rollback DELETE FROM role_permissions WHERE permission_id IN (SELECT id FROM permissions WHERE code = 'view_as:read');
--rollback DELETE FROM permissions WHERE code = 'view_as:read';
