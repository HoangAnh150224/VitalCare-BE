--liquibase formatted sql

--changeset vitalcare:064-desk-payment-choice
--comment UC-01 step 4: the patient chose to pay the examination fee at the desk on the day. Kept so the slip, the desk and the patient's phone all say so; null means they pay online (or have not chosen).

ALTER TABLE appointment ADD COLUMN desk_payment_chosen_at TIMESTAMP WITH TIME ZONE;

--rollback ALTER TABLE appointment DROP COLUMN desk_payment_chosen_at;
