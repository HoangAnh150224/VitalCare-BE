--liquibase formatted sql

--changeset vitalcare:036-visit-away-and-referral
--comment A visit can leave the room for its ordered services or for another doctor and come back: when it came back (it is then called first), and which visit a same-day referral came from.

ALTER TABLE appointment
    ADD COLUMN returned_at TIMESTAMPTZ,
    ADD COLUMN referred_from_id BIGINT REFERENCES appointment (id);

CREATE INDEX idx_appointment_referred_from ON appointment (referred_from_id) WHERE referred_from_id IS NOT NULL;

--rollback DROP INDEX idx_appointment_referred_from;
--rollback ALTER TABLE appointment DROP COLUMN referred_from_id, DROP COLUMN returned_at;
