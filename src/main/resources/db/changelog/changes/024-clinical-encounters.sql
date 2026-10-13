--liquibase formatted sql

--changeset vitalcare:024-clinical-encounters
--comment Clinical encounters and medical records: follow-up column, permissions, grants and clinic scopes.

ALTER TABLE clinical_encounter ADD COLUMN follow_up text;

-- One record per patient; the service creates it on first write.
CREATE UNIQUE INDEX uq_medical_record_customer ON medical_record (customer_id) WHERE deleted_at IS NULL;

INSERT INTO permissions (code, name, description, system_permission) VALUES
  ('clinical_encounters:read',  'View clinical encounters',   'List and open the encounters recorded for patients', TRUE),
  ('clinical_encounters:write', 'Record clinical encounters', 'Create, edit and complete an encounter',             TRUE),
  ('medical_records:read',      'View medical records',       'Open a patient''s health background',                TRUE),
  ('medical_records:write',     'Edit medical records',       'Update a patient''s health background',              TRUE);

-- SRS 3.1.3: Doctor and Healthcare Staff, which here is the nurse and the front desk.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN ('clinical_encounters:read', 'clinical_encounters:write',
                                 'medical_records:read', 'medical_records:write')
WHERE r.code IN ('DOCTOR', 'NURSE', 'MANAGER');

-- BR-04: an encounter belongs to the clinic it happened at.
INSERT INTO row_level_policy (kind, role_id, resource, action, name, policy_group, description, scope, enabled, created_at)
SELECT 'SCOPE', r.id, 'clinical_encounters', a.action, 'Cơ sở của mình', 'clinic',
       'Lần khám tại cơ sở của mình.',
       '{"field": "clinicId", "op": "eq", "value": {"ctx": "user.clinicId"}}'::jsonb, TRUE, now()
FROM roles r
CROSS JOIN (VALUES ('read'), ('write')) AS a(action)
WHERE r.code IN ('DOCTOR', 'NURSE', 'MANAGER');

--rollback DELETE FROM row_level_policy WHERE resource = 'clinical_encounters';
--rollback DELETE FROM role_permissions WHERE permission_id IN (SELECT id FROM permissions WHERE code LIKE 'clinical_encounters:%' OR code LIKE 'medical_records:%');
--rollback DELETE FROM permissions WHERE code LIKE 'clinical_encounters:%' OR code LIKE 'medical_records:%';
--rollback DROP INDEX uq_medical_record_customer;
--rollback ALTER TABLE clinical_encounter DROP COLUMN follow_up;
