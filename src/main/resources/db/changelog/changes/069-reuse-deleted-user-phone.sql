--liquibase formatted sql
--changeset vitalcare:069-reuse-deleted-user-phone
-- Retain archived identities and their history while allowing a new account.
ALTER TABLE users DROP CONSTRAINT uq_users_phone;
CREATE UNIQUE INDEX uq_users_phone ON users (phone) WHERE deleted_at IS NULL;
