--liquibase formatted sql

--changeset vitalcare:028-dependents
--comment One account, several patient profiles: the owner's own and the relatives they register visits for.

-- A dependent has no account: it belongs to the account owner's own profile instead.
ALTER TABLE customer ALTER COLUMN user_id DROP NOT NULL;
ALTER TABLE customer
    ADD COLUMN guardian_id  bigint CONSTRAINT fk_customer_guardian REFERENCES customer (id) ON DELETE RESTRICT,
    ADD COLUMN full_name    varchar(255),
    ADD COLUMN phone        varchar(32),
    ADD COLUMN relationship varchar(32),
    ADD COLUMN national_id  varchar(32);
ALTER TABLE customer ADD CONSTRAINT ck_customer_belongs CHECK (user_id IS NOT NULL OR guardian_id IS NOT NULL);
CREATE INDEX idx_customer_guardian ON customer (guardian_id);

INSERT INTO permissions (code, name, description, system_permission) VALUES
  ('my_profiles:read',  'View own patient profiles', 'See the patient profiles under the account: the owner and relatives', TRUE),
  ('my_profiles:write', 'Edit own patient profiles', 'Fill in one''s own profile and add relatives',           TRUE);

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code LIKE 'my_profiles:%'
WHERE r.code = 'CUSTOMER';

--rollback DELETE FROM role_permissions WHERE permission_id IN (SELECT id FROM permissions WHERE code LIKE 'my_profiles:%');
--rollback DELETE FROM permissions WHERE code LIKE 'my_profiles:%';
--rollback DROP INDEX idx_customer_guardian;
--rollback ALTER TABLE customer DROP CONSTRAINT ck_customer_belongs;
--rollback ALTER TABLE customer DROP COLUMN guardian_id, DROP COLUMN full_name, DROP COLUMN phone, DROP COLUMN relationship, DROP COLUMN national_id;
