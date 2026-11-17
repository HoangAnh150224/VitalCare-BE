--liquibase formatted sql

--changeset vitalcare:085-technician-schedules-result-returns splitStatements:false
--comment The service technician who records a result tells the patient when to come back for it, from the order. TECHNICIAN may read and set result returns at its own clinic; the front desk only sees them and verifies arrivals.

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN ('result_returns:read', 'result_returns:write')
WHERE r.code = 'TECHNICIAN'
  AND NOT EXISTS (SELECT 1 FROM role_permissions x WHERE x.role_id = r.id AND x.permission_id = p.id);

INSERT INTO row_level_policy (kind, role_id, resource, action, name, policy_group, description, scope, enabled, created_at)
SELECT 'SCOPE', r.id, 'result_returns', x.action, 'Cơ sở của mình', 'clinic',
       'Của cơ sở mình.',
       '{"field": "clinicId", "op": "eq", "value": {"ctx": "user.clinicId"}}'::jsonb, TRUE, now()
FROM roles r
CROSS JOIN (VALUES ('read'), ('write')) AS x(action)
WHERE r.code = 'TECHNICIAN'
  AND NOT EXISTS (SELECT 1 FROM row_level_policy p
                  WHERE p.role_id = r.id AND p.resource = 'result_returns' AND p.action = x.action);

--rollback DELETE FROM row_level_policy WHERE resource = 'result_returns' AND role_id IN (SELECT id FROM roles WHERE code = 'TECHNICIAN');
--rollback DELETE FROM role_permissions WHERE role_id IN (SELECT id FROM roles WHERE code = 'TECHNICIAN') AND permission_id IN (SELECT id FROM permissions WHERE code LIKE 'result_returns:%');
