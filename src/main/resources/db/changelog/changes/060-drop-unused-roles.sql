--liquibase formatted sql

--changeset vitalcare:060-drop-unused-roles
--comment The accounts have seven roles: Guest, Patient, Caregiver, Doctor, Healthcare Staff, Clinic Administrator and System Administrator. A caregiver is a Guest or Patient someone has shared with (sharing_permission), not a role of its own, and nobody is "medical staff" apart from being a nurse or the front desk, so CARE_GIVER and MEDICAL_STAFF go. Whoever held one keeps their other roles.

DELETE FROM user_roles WHERE role_id IN (SELECT id FROM roles WHERE code IN ('CARE_GIVER', 'MEDICAL_STAFF'));
DELETE FROM roles WHERE code IN ('CARE_GIVER', 'MEDICAL_STAFF');

--rollback -- Not restored: both roles were unused.
