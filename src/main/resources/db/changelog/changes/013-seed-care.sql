--liquibase formatted sql

--changeset vitalcare:013-seed-care
--comment The CUSTOMER role, permissions for customers and appointments, and a clinic to book at.

-- What a self-registered account holds. A system role, like the other three:
-- registration assigns it by code, so renaming or deleting it would break
-- sign-up.
INSERT INTO roles (code, name, description, system_role) VALUES
  ('CUSTOMER', 'Customer', 'Self-registered account: books and manages its own appointments.', TRUE);

-- customers / appointments are the staff side, my_appointments the customer's
-- own. They are separate resources rather than one resource with a row-level
-- scope because the two sides are different screens with different rules:
-- a customer never chooses whose appointment it is.
--
-- activate and check_in are actions of their own rather than part of write:
-- turning somebody into a patient is not the same grant as correcting their
-- address.
INSERT INTO permissions (code, name, description, system_permission) VALUES
  ('customers:read',          'View customers',          'List and open customer and patient profiles',           TRUE),
  ('customers:write',         'Edit customers',          'Correct a customer''s profile details',                 TRUE),
  ('customers:activate',      'Activate patients',       'Turn a neutral customer into a patient',                TRUE),
  ('appointments:read',       'View appointments',       'List and open every appointment',                       TRUE),
  ('appointments:write',      'Manage appointments',     'Book on a customer''s behalf and cancel appointments',  TRUE),
  ('appointments:check_in',   'Check in appointments',   'Record that a customer has arrived for an appointment', TRUE),
  ('my_appointments:read',    'View own appointments',   'See the appointments booked under one''s own account',  TRUE),
  ('my_appointments:write',   'Book own appointments',   'Book and cancel one''s own appointments',               TRUE);

-- ADMIN gets the staff side, spelled out for the reason 005 gives. Not
-- my_appointments: an administrator has no customer profile, so those screens
-- would only ever answer that there is nothing to show.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code LIKE 'customers:%' OR p.code LIKE 'appointments:%'
WHERE r.code = 'ADMIN';

-- MANAGER is the clinic's front desk: sees customers, activates them, runs the
-- appointment book. Not customers:write -- a profile correction is the
-- customer's own or an administrator's.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN ('customers:read', 'customers:activate',
                                 'appointments:read', 'appointments:write',
                                 'appointments:check_in')
WHERE r.code = 'MANAGER';

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code LIKE 'my_appointments:%'
WHERE r.code = 'CUSTOMER';

-- One clinic so a booking has somewhere to go before clinic management
-- exists. The UUID is fixed so that environments agree on it.
INSERT INTO clinic (clinic_id, clinic_name, address, contact_phone, operating_status, created_at, version) VALUES
  ('5b1f8c2e-6a3d-4f7e-9c41-2d8e0a7b3c19', 'VitalCare Clinic', '1 Demo Street, Ho Chi Minh City', '+84900000000', 'OPERATING',
   TIMESTAMP WITH TIME ZONE '2024-01-01 00:00:00+00', 0);

--rollback DELETE FROM clinic WHERE clinic_id = '5b1f8c2e-6a3d-4f7e-9c41-2d8e0a7b3c19';
--rollback DELETE FROM role_permissions WHERE permission_id IN (SELECT id FROM permissions WHERE code LIKE 'customers:%' OR code LIKE 'appointments:%' OR code LIKE 'my_appointments:%');
--rollback DELETE FROM permissions WHERE code LIKE 'customers:%' OR code LIKE 'appointments:%' OR code LIKE 'my_appointments:%';
--rollback DELETE FROM user_roles WHERE role_id IN (SELECT id FROM roles WHERE code = 'CUSTOMER');
--rollback DELETE FROM roles WHERE code = 'CUSTOMER';
