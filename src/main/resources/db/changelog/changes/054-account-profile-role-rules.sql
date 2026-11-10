--liquibase formatted sql

--changeset vitalcare:054-account-profile-role-rules
--comment Separate Guest/Patient access and permit patient profiles that have no account.

-- A walk-in patient can have a profile without an account and without being a
-- dependent of somebody else's account. CUSTOMER was the old combined
-- Guest/Patient role; retain its grants on PATIENT, then narrow GUEST below.
ALTER TABLE customer DROP CONSTRAINT ck_customer_belongs;

UPDATE roles
SET code = 'GUEST',
    name = 'Guest',
    description = 'Self-registered account that can view clinics, book visits and use consent-scoped caregiver features.'
WHERE code = 'CUSTOMER';

INSERT INTO roles (code, name, description, system_role)
SELECT 'PATIENT', 'Patient', 'Account linked to an activated patient profile.', TRUE
WHERE NOT EXISTS (SELECT 1 FROM roles WHERE code = 'PATIENT');

UPDATE roles SET name = 'Patient', description = 'Account linked to an activated patient profile.', system_role = TRUE
WHERE code = 'PATIENT';

-- Preserve the complete existing patient portal grant set under PATIENT.
INSERT INTO role_permissions (role_id, permission_id)
SELECT patient.id, rp.permission_id
FROM roles guest
JOIN role_permissions rp ON rp.role_id = guest.id
JOIN roles patient ON patient.code = 'PATIENT'
WHERE guest.code = 'GUEST'
  AND NOT EXISTS (
      SELECT 1 FROM role_permissions existing
      WHERE existing.role_id = patient.id AND existing.permission_id = rp.permission_id
  );

-- A Guest can book, update their own basic profile, chat with a clinic, and
-- use patient data explicitly shared with them as a caregiver. The service
-- still checks active consent for every shared-patient operation.
DELETE FROM role_permissions rp
USING roles guest, permissions p
WHERE rp.role_id = guest.id
  AND rp.permission_id = p.id
  AND guest.code = 'GUEST'
  AND p.code NOT IN (
      'my_appointments:read', 'my_appointments:write',
      'my_profiles:read', 'my_profiles:write',
      'my_conversations:read', 'my_conversations:write',
      'shared_patients:read', 'emergency_alerts:read', 'emergency_alerts:write'
  );

-- Move already activated account owners from Guest to Patient. Accountless
-- walk-ins remain accountless and receive no role until an account is linked.
INSERT INTO user_roles (user_id, role_id)
SELECT c.user_id, patient.id
FROM customer c
JOIN roles patient ON patient.code = 'PATIENT'
WHERE c.user_id IS NOT NULL AND c.status = 'PATIENT'
  AND NOT EXISTS (
      SELECT 1 FROM user_roles existing
      WHERE existing.user_id = c.user_id AND existing.role_id = patient.id
  );

DELETE FROM user_roles ur
USING customer c, roles guest
WHERE ur.user_id = c.user_id
  AND ur.role_id = guest.id
  AND c.status = 'PATIENT'
  AND guest.code = 'GUEST';
