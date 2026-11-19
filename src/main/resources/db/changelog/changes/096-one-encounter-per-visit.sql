--liquibase formatted sql

--changeset vitalcare:096-one-encounter-per-visit splitStatements:false
--comment One live encounter per registered visit: the visit's result and bill hang on it. The service already refuses a second one under the visit's lock; this index is the database's own guard. Visits that already carry several encounters (test data started twice) are left as they are and the index waits for them: a duplicate here would stop every deploy.
DO $migration$
BEGIN
    IF EXISTS (
        SELECT 1 FROM clinical_encounter
        WHERE appointment_id IS NOT NULL AND deleted_at IS NULL
        GROUP BY appointment_id HAVING count(*) > 1
    ) THEN
        RAISE NOTICE 'uq_clinical_encounter_visit not created: some visits already have several encounters';
    ELSE
        CREATE UNIQUE INDEX IF NOT EXISTS uq_clinical_encounter_visit
            ON clinical_encounter (appointment_id)
            WHERE appointment_id IS NOT NULL AND deleted_at IS NULL;
    END IF;
END
$migration$;
--rollback DROP INDEX IF EXISTS uq_clinical_encounter_visit;
