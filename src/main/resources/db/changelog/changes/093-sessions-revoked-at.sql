--liquibase formatted sql

--changeset vitalcare:093-sessions-revoked-at
--comment When an account's sessions were last ended; an access token issued before it is refused (KAN-80).

ALTER TABLE users ADD COLUMN sessions_revoked_at TIMESTAMPTZ;
