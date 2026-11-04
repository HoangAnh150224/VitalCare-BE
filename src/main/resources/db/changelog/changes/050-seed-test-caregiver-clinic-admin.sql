--liquibase formatted sql

--changeset vitalcare:050-seed-test-caregiver-clinic-admin
--comment Two accounts for the test team, with the same password as their seed customer (VitalCareTest#2026): a clinic administrator at VitalCare Clinic (0900000031) and a caregiver (0900000041) whom the seed customer 0000000131 — or, where that account does not exist, the demo patient 0900000501 — shares vitals and alerts with. Each insert is skipped when the number is already taken, so it never overwrites somebody's account.

-- Clinic administrator: an account, its role, and the staff record and membership at the clinic.
INSERT INTO users (phone, password_hash, full_name, status, created_at)
SELECT '+84900000031', '$2a$10$IthJ0J/8bj.DX595lw6Doewym8FL6aUGRfXLHLWNrZ7QdIeLtk1Eu', 'QTPK. Tài khoản Test', 'ACTIVE', now()
WHERE NOT EXISTS (SELECT 1 FROM users WHERE phone = '+84900000031');

INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id FROM users u, roles r
WHERE u.phone = '+84900000031' AND r.code = 'CLINIC_ADMIN'
  AND NOT EXISTS (SELECT 1 FROM user_roles x WHERE x.user_id = u.id AND x.role_id = r.id);

INSERT INTO employee (user_id, clinic_id, staff_type, clinic_position, status, created_at, version)
SELECT u.id, '5b1f8c2e-6a3d-4f7e-9c41-2d8e0a7b3c19', 'CLINIC_ADMIN', 'Quản trị phòng khám', 'ACTIVE', now(), 0
FROM users u
WHERE u.phone = '+84900000031' AND NOT EXISTS (SELECT 1 FROM employee e WHERE e.user_id = u.id);

UPDATE employee SET employee_code = 'NV' || lpad(id::text, 6, '0')
WHERE employee_code IS NULL AND user_id = (SELECT id FROM users WHERE phone = '+84900000031');

INSERT INTO staff_membership (employee_id, clinic_id, status, started_at, created_at)
SELECT e.id, e.clinic_id, 'ACTIVE', now(), now()
FROM employee e JOIN users u ON u.id = e.user_id
WHERE u.phone = '+84900000031'
  AND NOT EXISTS (SELECT 1 FROM staff_membership m WHERE m.employee_id = e.id AND m.status = 'ACTIVE');

-- Caregiver: a customer account like a self-registration (role CUSTOMER, a neutral customer record).
INSERT INTO users (phone, password_hash, full_name, status, created_at)
SELECT '+84900000041', '$2a$10$IthJ0J/8bj.DX595lw6Doewym8FL6aUGRfXLHLWNrZ7QdIeLtk1Eu', 'Người chăm sóc Test', 'ACTIVE', now()
WHERE NOT EXISTS (SELECT 1 FROM users WHERE phone = '+84900000041');

INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id FROM users u, roles r
WHERE u.phone = '+84900000041' AND r.code = 'CUSTOMER'
  AND NOT EXISTS (SELECT 1 FROM user_roles x WHERE x.user_id = u.id AND x.role_id = r.id);

INSERT INTO customer (user_id, created_at, version)
SELECT u.id, now(), 0 FROM users u
WHERE u.phone = '+84900000041' AND NOT EXISTS (SELECT 1 FROM customer c WHERE c.user_id = u.id);

UPDATE customer SET customer_code = 'KH' || lpad(id::text, 6, '0')
WHERE customer_code IS NULL AND user_id = (SELECT id FROM users WHERE phone = '+84900000041');

-- The grant that makes them a caregiver: from the seed customer, else the demo patient; at most two in force (BF-06).
INSERT INTO sharing_permission (customer_id, caregiver_user_id, share_vitals, share_alerts, share_appointments,
                                emergency_phone, note, status, created_at, version)
SELECT p.id, cg.id, TRUE, TRUE, TRUE, '+84900000041', 'Tài khoản test của nhóm kiểm thử', 'ACTIVE', now(), 0
FROM (
    SELECT c.id FROM customer c JOIN users u ON u.id = c.user_id
    WHERE u.phone IN ('+84000000131', '+84900000501') AND c.deleted_at IS NULL
    ORDER BY (u.phone = '+84000000131') DESC, c.id
    LIMIT 1
) p
CROSS JOIN (SELECT id FROM users WHERE phone = '+84900000041') cg
WHERE NOT EXISTS (SELECT 1 FROM sharing_permission s WHERE s.customer_id = p.id AND s.caregiver_user_id = cg.id
                    AND s.status = 'ACTIVE' AND s.deleted_at IS NULL)
  AND (SELECT count(*) FROM sharing_permission s WHERE s.customer_id = p.id AND s.status = 'ACTIVE'
         AND s.deleted_at IS NULL AND (s.expires_at IS NULL OR s.expires_at > now())) < 2;

--rollback DELETE FROM sharing_permission WHERE caregiver_user_id = (SELECT id FROM users WHERE phone = '+84900000041') AND note = 'Tài khoản test của nhóm kiểm thử';
--rollback DELETE FROM customer WHERE user_id = (SELECT id FROM users WHERE phone = '+84900000041');
--rollback DELETE FROM user_roles WHERE user_id IN (SELECT id FROM users WHERE phone IN ('+84900000031', '+84900000041'));
--rollback DELETE FROM staff_membership WHERE employee_id = (SELECT e.id FROM employee e JOIN users u ON u.id = e.user_id WHERE u.phone = '+84900000031');
--rollback DELETE FROM employee WHERE user_id = (SELECT id FROM users WHERE phone = '+84900000031');
--rollback DELETE FROM users WHERE phone IN ('+84900000031', '+84900000041');
