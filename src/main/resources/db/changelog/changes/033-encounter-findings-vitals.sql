--liquibase formatted sql

--changeset vitalcare:033-encounter-findings-vitals
--comment The visit form gets the examination findings (SRS 3.19 wireframe) and the vital signs measured at the visit; the diagnosis and concluding become the doctor's.

-- What was found on examination, apart from what the patient reported.
-- Vital signs taken at the visit by hand; a device's readings stay in
-- vital_signs. The ranges only stop a slip of the keyboard, not a sick patient.
ALTER TABLE clinical_encounter
    ADD COLUMN examination_findings text,
    ADD COLUMN temperature      numeric(4, 1) CONSTRAINT ck_encounter_temperature CHECK (temperature BETWEEN 30 AND 45),
    ADD COLUMN pulse            integer       CONSTRAINT ck_encounter_pulse CHECK (pulse BETWEEN 20 AND 250),
    ADD COLUMN respiratory_rate integer       CONSTRAINT ck_encounter_respiratory_rate CHECK (respiratory_rate BETWEEN 4 AND 80),
    ADD COLUMN systolic_bp      integer       CONSTRAINT ck_encounter_systolic_bp CHECK (systolic_bp BETWEEN 50 AND 260),
    ADD COLUMN diastolic_bp     integer       CONSTRAINT ck_encounter_diastolic_bp CHECK (diastolic_bp BETWEEN 20 AND 200),
    ADD COLUMN spo2             integer       CONSTRAINT ck_encounter_spo2 CHECK (spo2 BETWEEN 50 AND 100),
    ADD COLUMN weight_kg        numeric(5, 1) CONSTRAINT ck_encounter_weight CHECK (weight_kg BETWEEN 0.5 AND 400),
    ADD COLUMN height_cm        numeric(5, 1) CONSTRAINT ck_encounter_height CHECK (height_cm BETWEEN 30 AND 250);

-- Recording the diagnosis and concluding the visit is the doctor's: a nurse
-- may write the rest of the form, but what the visit concluded is signed off
-- by whoever holds this.
INSERT INTO permissions (code, name, description, system_permission) VALUES
  ('clinical_encounters:conclude', 'Conclude clinical encounters', 'Record the diagnosis and complete an encounter', TRUE);

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code = 'clinical_encounters:conclude'
WHERE r.code = 'DOCTOR';

-- The front desk reads encounters (to bill, to answer a patient) and no longer writes them.
DELETE FROM role_permissions
WHERE role_id IN (SELECT id FROM roles WHERE code = 'MANAGER')
  AND permission_id IN (SELECT id FROM permissions WHERE code = 'clinical_encounters:write');

--rollback INSERT INTO role_permissions (role_id, permission_id) SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code = 'clinical_encounters:write' WHERE r.code = 'MANAGER';
--rollback DELETE FROM role_permissions WHERE permission_id IN (SELECT id FROM permissions WHERE code = 'clinical_encounters:conclude');
--rollback DELETE FROM permissions WHERE code = 'clinical_encounters:conclude';
--rollback ALTER TABLE clinical_encounter DROP COLUMN height_cm, DROP COLUMN weight_kg, DROP COLUMN spo2, DROP COLUMN diastolic_bp, DROP COLUMN systolic_bp, DROP COLUMN respiratory_rate, DROP COLUMN pulse, DROP COLUMN temperature, DROP COLUMN examination_findings;
