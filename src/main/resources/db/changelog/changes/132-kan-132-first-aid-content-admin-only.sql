--liquibase formatted sql

--changeset vitalcare:132-kan-132-first-aid-content-admin-only
--comment Managing first-aid content (reading drafts and history, writing, reviewing, publishing, archiving) is the clinic administrator's: the screens are closed to a doctor, but 037 had also granted first_aid_articles:* to DOCTOR (and to NURSE / MEDICAL_STAFF, since dropped), so the API still answered a doctor's token. The grants now match the screens. Published articles stay open to everyone on /api/public/first_aid, which asks for no permission.
DELETE FROM role_permissions
WHERE permission_id IN (SELECT id FROM permissions WHERE code LIKE 'first_aid_articles:%')
  AND role_id IN (SELECT id FROM roles WHERE code NOT IN ('ADMIN', 'CLINIC_ADMIN'));

--rollback INSERT INTO role_permissions (role_id, permission_id)
--rollback SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code LIKE 'first_aid_articles:%'
--rollback WHERE r.code = 'DOCTOR'
--rollback   AND NOT EXISTS (SELECT 1 FROM role_permissions x WHERE x.role_id = r.id AND x.permission_id = p.id);
