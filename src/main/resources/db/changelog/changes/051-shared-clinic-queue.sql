--liquibase formatted sql

--changeset vitalcare:051-shared-clinic-queue
--comment BF-02 shared queue: a visit is registered for a clinic and a day with no doctor and no expected time; it takes the clinic's next number, and the doctor of whichever room calls it in. Visits registered before keep their doctor and time.

ALTER TABLE appointment ALTER COLUMN start_time DROP NOT NULL;
ALTER TABLE appointment ALTER COLUMN end_time DROP NOT NULL;
CREATE INDEX idx_appointment_clinic_day_queue ON appointment (clinic_id, appointment_date, queue_number) WHERE deleted_at IS NULL;

--rollback DROP INDEX idx_appointment_clinic_day_queue;
