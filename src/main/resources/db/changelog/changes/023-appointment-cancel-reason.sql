--liquibase formatted sql

--changeset vitalcare:023-appointment-cancel-reason
--comment Why an appointment was cancelled: required when the clinic cancels a customer's visit.

ALTER TABLE appointment ADD COLUMN cancel_reason varchar(500);

--rollback ALTER TABLE appointment DROP COLUMN cancel_reason;
