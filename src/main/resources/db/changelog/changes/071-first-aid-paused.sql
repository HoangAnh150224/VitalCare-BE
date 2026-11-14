--liquibase formatted sql
--changeset vitalcare:071-first-aid-paused
ALTER TABLE first_aid_article DROP CONSTRAINT ck_first_aid_article_status;
ALTER TABLE first_aid_article ADD CONSTRAINT ck_first_aid_article_status
CHECK (status IN ('DRAFT', 'PENDING_REVIEW', 'REVIEWED', 'PUBLISHED', 'ARCHIVED', 'PAUSED'));
