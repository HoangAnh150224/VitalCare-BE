--liquibase formatted sql

--changeset vitalcare:038-support-chat
--comment Support chat (SRS 3.16, BR-30) between a clinic's staff and a signed-in customer or a guest who has not signed in. Builds on the conversation and message tables of 008, unused until now.

-- A conversation is with one clinic. The other side is a customer's account
-- (customer_user_id) or a guest, who proves it is them with a token only its
-- browser holds; only the token's SHA-256 is kept. contact_name/phone are the
-- name and number staff see, as given when the conversation started.
ALTER TABLE conversation
    ADD COLUMN clinic_id            UUID,
    ADD COLUMN customer_user_id     BIGINT,
    ADD COLUMN contact_name         VARCHAR(100),
    ADD COLUMN contact_phone        VARCHAR(20),
    ADD COLUMN guest_token_hash     VARCHAR(64),
    ADD COLUMN last_message_at      TIMESTAMPTZ,
    ADD COLUMN last_message_preview VARCHAR(200),
    ADD COLUMN last_sender_kind     VARCHAR(16),
    ADD COLUMN customer_read_at     TIMESTAMPTZ,
    ADD COLUMN staff_read_at        TIMESTAMPTZ,
    ALTER COLUMN status SET DEFAULT 'OPEN',
    ADD CONSTRAINT fk_conversation_clinic FOREIGN KEY (clinic_id) REFERENCES clinic (clinic_id),
    ADD CONSTRAINT fk_conversation_customer_user FOREIGN KEY (customer_user_id) REFERENCES users (id),
    ADD CONSTRAINT uq_conversation_guest_token UNIQUE (guest_token_hash);
CREATE INDEX idx_conversation_clinic_last ON conversation (clinic_id, last_message_at DESC);
CREATE INDEX idx_conversation_customer ON conversation (customer_user_id);

-- Who wrote a message: GUEST, CUSTOMER or STAFF (sender_id is the account,
-- null for a guest). An image is stored in the file store under its key.
ALTER TABLE message
    ADD COLUMN sender_kind     VARCHAR(16),
    ADD COLUMN attachment_key  VARCHAR(255),
    ADD COLUMN attachment_type VARCHAR(64),
    ADD COLUMN attachment_size BIGINT;
CREATE INDEX idx_message_conversation ON message (conversation_id, id);

INSERT INTO permissions (code, name, description, system_permission) VALUES
  ('conversations:read',     'View support chats',  'See the clinic''s conversations with patients and guests', TRUE),
  ('conversations:write',    'Answer support chats', 'Reply, send images and close conversations', TRUE),
  ('my_conversations:read',  'View own chats',      'See one''s own conversations with clinics', TRUE),
  ('my_conversations:write', 'Chat with a clinic',  'Start a conversation with a clinic and write in it', TRUE);

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON
       (p.code LIKE 'conversations:%'    AND r.code IN ('ADMIN', 'CLINIC_ADMIN', 'MANAGER', 'DOCTOR', 'NURSE', 'MEDICAL_STAFF'))
    OR (p.code LIKE 'my_conversations:%' AND r.code IN ('CUSTOMER', 'PATIENT'))
WHERE NOT EXISTS (SELECT 1 FROM role_permissions x WHERE x.role_id = r.id AND x.permission_id = p.id);

-- Staff see their own clinic's conversations; the system administrator every clinic's.
INSERT INTO row_level_policy (kind, role_id, resource, action, name, policy_group, description, scope, enabled, created_at)
SELECT 'SCOPE', r.id, 'conversations', x.action,
       CASE WHEN r.code = 'ADMIN' THEN 'Mọi cơ sở' ELSE 'Cơ sở của mình' END, 'clinic',
       CASE WHEN r.code = 'ADMIN' THEN 'Hội thoại của mọi cơ sở.' ELSE 'Hội thoại tại cơ sở của mình.' END,
       CASE WHEN r.code = 'ADMIN' THEN '{"all": []}'::jsonb
            ELSE '{"field": "clinicId", "op": "eq", "value": {"ctx": "user.clinicId"}}'::jsonb END,
       TRUE, now()
FROM roles r
CROSS JOIN (VALUES ('read'), ('write')) AS x(action)
WHERE r.code IN ('ADMIN', 'CLINIC_ADMIN', 'MANAGER', 'DOCTOR', 'NURSE', 'MEDICAL_STAFF');

--rollback DELETE FROM row_level_policy WHERE resource = 'conversations';
--rollback DELETE FROM role_permissions WHERE permission_id IN (SELECT id FROM permissions WHERE code LIKE 'conversations:%' OR code LIKE 'my_conversations:%');
--rollback DELETE FROM permissions WHERE code LIKE 'conversations:%' OR code LIKE 'my_conversations:%';
--rollback DROP INDEX idx_message_conversation;
--rollback ALTER TABLE message DROP COLUMN sender_kind, DROP COLUMN attachment_key, DROP COLUMN attachment_type, DROP COLUMN attachment_size;
--rollback DROP INDEX idx_conversation_customer;
--rollback DROP INDEX idx_conversation_clinic_last;
--rollback ALTER TABLE conversation DROP CONSTRAINT uq_conversation_guest_token, DROP CONSTRAINT fk_conversation_customer_user, DROP CONSTRAINT fk_conversation_clinic, DROP COLUMN clinic_id, DROP COLUMN customer_user_id, DROP COLUMN contact_name, DROP COLUMN contact_phone, DROP COLUMN guest_token_hash, DROP COLUMN last_message_at, DROP COLUMN last_message_preview, DROP COLUMN last_sender_kind, DROP COLUMN customer_read_at, DROP COLUMN staff_read_at;
