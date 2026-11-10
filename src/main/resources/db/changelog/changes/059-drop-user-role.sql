--liquibase formatted sql

--changeset vitalcare:059-drop-user-role
--comment USER (sign-in with no grants) is not one of the account roles: an account that is not staff is a Guest. Whoever still holds USER becomes a Guest, with the empty profile registration would have given them, and the role goes.

-- A Guest's own pages read their profile, so a live account without one gets it here.
INSERT INTO customer (user_id, status, activity_status, created_at, version)
SELECT u.id, 'NEUTRAL', 'active', now(), 0
FROM users u
JOIN user_roles ur ON ur.user_id = u.id
JOIN roles r ON r.id = ur.role_id AND r.code = 'USER'
WHERE u.deleted_at IS NULL
  AND NOT EXISTS (SELECT 1 FROM customer c WHERE c.user_id = u.id AND c.deleted_at IS NULL);

UPDATE customer SET customer_code = 'KH' || lpad(id::text, 6, '0')
WHERE customer_code IS NULL
  AND user_id IN (SELECT ur.user_id FROM user_roles ur JOIN roles r ON r.id = ur.role_id WHERE r.code = 'USER');

INSERT INTO user_roles (user_id, role_id)
SELECT ur.user_id, guest.id
FROM user_roles ur
JOIN roles r ON r.id = ur.role_id AND r.code = 'USER'
JOIN roles guest ON guest.code = 'GUEST'
WHERE NOT EXISTS (SELECT 1 FROM user_roles x WHERE x.user_id = ur.user_id AND x.role_id = guest.id);

DELETE FROM user_roles WHERE role_id = (SELECT id FROM roles WHERE code = 'USER');
DELETE FROM roles WHERE code = 'USER';

--rollback INSERT INTO roles (code, name, description, system_role) VALUES ('USER', 'User', 'Signed-in access with no administrative grants yet.', TRUE);
