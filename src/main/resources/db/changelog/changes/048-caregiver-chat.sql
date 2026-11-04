--liquibase formatted sql

--changeset vitalcare:048-caregiver-chat
--comment BF-06: a caregiver writes to a clinic about the patient who shared with them. The conversation is the caregiver's (customer_user_id) and names the patient it is about; it is readable only while the sharing permission is in force.

ALTER TABLE conversation ADD COLUMN about_customer_id BIGINT REFERENCES customer (id);
ALTER TABLE conversation ADD COLUMN about_name VARCHAR(100);
CREATE INDEX idx_conversation_about ON conversation (customer_user_id, clinic_id, about_customer_id) WHERE deleted_at IS NULL;

--rollback DROP INDEX idx_conversation_about;
--rollback ALTER TABLE conversation DROP COLUMN about_name;
--rollback ALTER TABLE conversation DROP COLUMN about_customer_id;
