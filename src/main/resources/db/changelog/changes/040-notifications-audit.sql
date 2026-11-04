--liquibase formatted sql

--changeset vitalcare:040-notifications-audit
--comment FE-13 notifications to each account (the bell, live over STOMP), FE-20 the audit log of protected actions. Both tables came with 008 unused until now.

-- How loud a notification is (INFO, SUCCESS, WARNING, ERROR) and where it leads in the app.
ALTER TABLE notification
    ADD COLUMN level VARCHAR(16) NOT NULL DEFAULT 'INFO',
    ADD COLUMN link  VARCHAR(255),
    ADD CONSTRAINT ck_notification_level CHECK (level IN ('INFO', 'SUCCESS', 'WARNING', 'ERROR'));
CREATE INDEX idx_notification_user_created ON notification (user_id, created_at DESC) WHERE deleted_at IS NULL;
CREATE INDEX idx_notification_user_unread ON notification (user_id) WHERE read_at IS NULL AND deleted_at IS NULL;

CREATE INDEX idx_audit_log_occurred ON audit_log (occurred_at DESC);
CREATE INDEX idx_audit_log_entity ON audit_log (entity_type, entity_id);
CREATE INDEX idx_audit_log_actor ON audit_log (actor_id);

INSERT INTO permissions (code, name, description, system_permission) VALUES
  ('notifications:read',  'View own notifications',  'See the notifications sent to one''s own account', TRUE),
  ('notifications:write', 'Manage own notifications', 'Mark one''s own notifications read and remove them', TRUE),
  ('audit_logs:read',     'View the audit log',       'Search the record of protected actions; it cannot be edited', TRUE);

-- Every account has its own notifications, whatever its role (User.permissionCodes adds them for roles made
-- later). The audit log spans every clinic, so it is the system administrator's alone.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON
       p.code IN ('notifications:read', 'notifications:write')
    OR (p.code = 'audit_logs:read' AND r.code = 'ADMIN')
WHERE NOT EXISTS (SELECT 1 FROM role_permissions x WHERE x.role_id = r.id AND x.permission_id = p.id);

--rollback DELETE FROM role_permissions WHERE permission_id IN (SELECT id FROM permissions WHERE code LIKE 'notifications:%' OR code = 'audit_logs:read');
--rollback DELETE FROM permissions WHERE code LIKE 'notifications:%' OR code = 'audit_logs:read';
--rollback DROP INDEX idx_audit_log_actor;
--rollback DROP INDEX idx_audit_log_entity;
--rollback DROP INDEX idx_audit_log_occurred;
--rollback DROP INDEX idx_notification_user_unread;
--rollback DROP INDEX idx_notification_user_created;
--rollback ALTER TABLE notification DROP CONSTRAINT ck_notification_level, DROP COLUMN link, DROP COLUMN level;
