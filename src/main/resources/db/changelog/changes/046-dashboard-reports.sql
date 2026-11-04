--liquibase formatted sql

--changeset vitalcare:046-dashboard-reports
--comment BF-10 Clinic Monitoring Dashboard; BF-08 patient, device, vital sign and alert summary reports and their export.

INSERT INTO permissions (code, name, description, system_permission) VALUES
  ('monitoring_dashboard:read', 'View the monitoring dashboard', 'Patients monitored, devices, open alerts, today''s visits and data intake at a glance', TRUE),
  ('reports:read',   'View summary reports', 'Patient, device, vital sign and alert summaries over a period', TRUE),
  ('reports:export', 'Export summary reports', 'Download the summary reports as CSV', TRUE);

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON
       (p.code = 'monitoring_dashboard:read' AND r.code IN ('ADMIN', 'CLINIC_ADMIN', 'MANAGER', 'DOCTOR', 'NURSE', 'MEDICAL_STAFF'))
    OR (p.code = 'reports:read' AND r.code IN ('ADMIN', 'CLINIC_ADMIN', 'MANAGER'))
    OR (p.code = 'reports:export' AND r.code IN ('ADMIN', 'CLINIC_ADMIN'))
WHERE NOT EXISTS (SELECT 1 FROM role_permissions x WHERE x.role_id = r.id AND x.permission_id = p.id);

--rollback DELETE FROM role_permissions WHERE permission_id IN (SELECT id FROM permissions WHERE code IN ('monitoring_dashboard:read','reports:read','reports:export'));
--rollback DELETE FROM permissions WHERE code IN ('monitoring_dashboard:read','reports:read','reports:export');
