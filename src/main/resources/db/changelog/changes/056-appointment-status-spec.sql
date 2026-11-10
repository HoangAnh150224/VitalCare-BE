--liquibase formatted sql

--changeset vitalcare:056-appointment-status-spec
--comment Appointment statuses as the booking spec names them: SCHEDULED is BOOKED; a visit checked in and paid is READY_FOR_EXAMINATION (BR-04), so CHECKED_IN now means at the desk and not paid yet.

UPDATE appointment SET status = 'BOOKED' WHERE status = 'SCHEDULED';
UPDATE appointment SET status = 'READY_FOR_EXAMINATION' WHERE status = 'CHECKED_IN' AND paid_at IS NOT NULL;

--rollback UPDATE appointment SET status = 'CHECKED_IN' WHERE status = 'READY_FOR_EXAMINATION';
--rollback UPDATE appointment SET status = 'SCHEDULED' WHERE status = 'BOOKED';
