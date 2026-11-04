--liquibase formatted sql

--changeset vitalcare:029-profile-identity
--comment What a patient profile says besides who and how to reach them: the kind of id and its issue date, the address in parts, ethnicity, nationality, occupation.

ALTER TABLE customer
    ADD COLUMN national_id_type      varchar(16),
    ADD COLUMN national_id_issued_on date,
    ADD COLUMN province              varchar(128),
    ADD COLUMN ward                  varchar(128),
    ADD COLUMN ethnicity             varchar(64),
    ADD COLUMN nationality           varchar(64),
    ADD COLUMN occupation            varchar(128);

--rollback ALTER TABLE customer DROP COLUMN national_id_type, DROP COLUMN national_id_issued_on, DROP COLUMN province, DROP COLUMN ward, DROP COLUMN ethnicity, DROP COLUMN nationality, DROP COLUMN occupation;
