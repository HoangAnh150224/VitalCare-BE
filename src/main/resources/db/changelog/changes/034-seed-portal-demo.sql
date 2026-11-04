--liquibase formatted sql

--changeset vitalcare:034-seed-portal-demo
--comment A demo patient account for the patient portal: the owner and a child, one visit ahead, one cancelled, and two concluded visits with their findings, results and bills.

-- Sign in as 0900000501 / Staff@123 (the demo staff's password). Dates are
-- relative to the day this runs, so the visit ahead is ahead on that day.
-- Seen by BS. Nguyễn Minh Khoa (+84900000011) at the clinic he works in.

INSERT INTO users (phone, email, password_hash, full_name, status, created_at) VALUES
  ('+84900000501', 'demo.benhnhan@vitalcare.local', '$2a$10$/Djqk9GYDFrRJaoEWjYPnufgE30PtNocsc64ZErWspUiye6arwnF6',
   'Trần Thu Trang', 'ACTIVE', now());

INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id FROM users u, roles r WHERE u.phone = '+84900000501' AND r.code = 'CUSTOMER';

-- The owner's own profile, already a patient (she has been seen before).
INSERT INTO customer (user_id, full_name, phone, date_of_birth, gender, national_id, national_id_type,
                      national_id_issued_on, province, ward, address, ethnicity, nationality, occupation,
                      status, patient_activated_at, patient_activation_source, created_at, version)
SELECT u.id, 'Trần Thu Trang', '+84900000501', DATE '1991-03-14', 'female', '001191004521', 'cccd',
       DATE '2021-06-02', 'Hà Nội', 'Phường Cầu Giấy', '25 Trần Thái Tông', 'Kinh', 'Việt Nam', 'Kế toán',
       'PATIENT', now() - INTERVAL '40 days', 'CHECK_IN', now(), 0
FROM users u WHERE u.phone = '+84900000501';

-- Her son, registered under her account.
INSERT INTO customer (guardian_id, full_name, phone, relationship, date_of_birth, gender, national_id,
                      national_id_type, province, ward, address, ethnicity, nationality,
                      status, patient_activated_at, patient_activation_source, created_at, version)
SELECT c.id, 'Nguyễn Minh Khang', '+84900000501', 'child', DATE '2018-09-21', 'male', '001218009873',
       'personal_id', 'Hà Nội', 'Phường Cầu Giấy', '25 Trần Thái Tông', 'Kinh', 'Việt Nam',
       'PATIENT', now() - INTERVAL '40 days', 'CHECK_IN', now(), 0
FROM customer c JOIN users u ON u.id = c.user_id WHERE u.phone = '+84900000501';

UPDATE customer SET customer_code = 'KH' || lpad(id::text, 6, '0')
WHERE phone = '+84900000501' AND customer_code IS NULL;

-- Four visits: two concluded (hers 12 days ago, her son's 40 days ago), one she
-- cancelled, one ahead.
INSERT INTO appointment (customer_id, employee_id, clinic_id, appointment_date, start_time, end_time, reason,
                         status, booking_code, queue_number, checked_in_at, cancelled_at, created_at, version)
SELECT c.id, e.id, e.clinic_id, v.day, v.starts, v.starts + INTERVAL '30 minutes', v.reason,
       v.status, v.code, v.queue, v.checked_in, v.cancelled, now(), 0
FROM (VALUES
  ('self',  CURRENT_DATE - 12, TIME '08:30', 'Ho, đau họng, sốt nhẹ về chiều',     'COMPLETED', 'DEMQ7K2A', 2,
            (CURRENT_DATE - 12) + TIME '08:20', NULL::timestamp),
  ('child', CURRENT_DATE - 40, TIME '09:00', 'Sổ mũi, ho đêm',                      'COMPLETED', 'DEMQ7K2B', 3,
            (CURRENT_DATE - 40) + TIME '08:50', NULL::timestamp),
  ('self',  CURRENT_DATE - 5,  TIME '10:00', 'Khám sức khỏe định kỳ',               'CANCELLED', 'DEMQ7K2C', 5,
            NULL::timestamp, (CURRENT_DATE - 6) + TIME '20:15'),
  ('self',  CURRENT_DATE + 2,  TIME '08:00', 'Tái khám họng, xem lại công thức máu', 'SCHEDULED', 'DEMQ7K2D', 1,
            NULL::timestamp, NULL::timestamp)
) AS v(who, day, starts, reason, status, code, queue, checked_in, cancelled)
JOIN users owner ON owner.phone = '+84900000501'
JOIN customer self ON self.user_id = owner.id
JOIN customer c ON (v.who = 'self' AND c.id = self.id) OR (v.who = 'child' AND c.guardian_id = self.id)
JOIN employee e ON e.user_id = (SELECT id FROM users WHERE phone = '+84900000011');

-- What the doctor found and concluded at the two concluded visits.
INSERT INTO clinical_encounter (appointment_id, customer_id, employee_id, clinic_id, started_at, ended_at,
                                chief_complaint, examination_findings, clinical_note, diagnosis, follow_up,
                                temperature, pulse, respiratory_rate, systolic_bp, diastolic_bp, spo2,
                                weight_kg, height_cm, status, created_at, version)
SELECT a.id, a.customer_id, a.employee_id, a.clinic_id,
       a.appointment_date + TIME '08:40', a.appointment_date + TIME '09:35',
       v.complaint, v.findings, v.note, v.diagnosis, v.follow_up,
       v.temperature, v.pulse, v.respiratory, v.systolic, v.diastolic, v.spo2, v.weight, v.height,
       'COMPLETED', now(), 0
FROM (VALUES
  ('DEMQ7K2A', 'Ho khan 4 ngày, đau rát họng, sốt nhẹ về chiều (37,5–38°C).',
   'Họng đỏ, amidan hai bên sưng nhẹ, không có mủ. Hạch dưới hàm không sờ thấy. Phổi thông khí đều, không ran.',
   'Không dị ứng thuốc đã biết. Đang cho con bú: tránh thuốc chống chỉ định.',
   'Viêm họng cấp',
   E'Uống nhiều nước ấm, súc họng bằng nước muối sinh lý 3 lần/ngày.\nNghỉ ngơi, hạn chế đồ lạnh và nói nhiều trong 1 tuần.\nTái khám sau 5 ngày, hoặc sớm hơn nếu sốt trên 38,5°C hay khó thở.',
   37.8, 88, 18, 118, 76, 98, 54.0, 158.0),
  ('DEMQ7K2B', 'Sổ mũi trong, ho về đêm 3 ngày, không sốt.',
   'Niêm mạc mũi phù nề, xuất tiết trong. Họng hơi đỏ. Phổi không ran. Tai hai bên bình thường.',
   NULL,
   'Viêm mũi họng cấp',
   E'Rửa mũi bằng nước muối sinh lý sáng và tối.\nCho bé uống đủ nước, giữ ấm cổ khi ngủ.\nĐưa bé đi khám lại nếu sốt, thở nhanh hoặc ho nhiều hơn.',
   36.9, 102, 24, NULL, NULL, 99, 22.5, 121.0)
) AS v(code, complaint, findings, note, diagnosis, follow_up,
       temperature, pulse, respiratory, systolic, diastolic, spo2, weight, height)
JOIN appointment a ON a.booking_code = v.code;

-- Her services, done and reviewed by the doctor.
INSERT INTO service_order (encounter_id, customer_id, clinic_id, service_id, unit_price, instruction, status,
                           expected_result_at, started_at, result_text, result_at, reviewed_at, created_at, version)
SELECT enc.id, enc.customer_id, enc.clinic_id, s.id, s.price, v.instruction, 'REVIEWED',
       enc.started_at + INTERVAL '1 hour', enc.started_at + INTERVAL '10 minutes', v.result,
       enc.started_at + INTERVAL '45 minutes', enc.started_at + INTERVAL '50 minutes', enc.started_at, 0
FROM (VALUES
  ('XN-CTM', NULL,
   E'Bạch cầu 11,2 G/L (tăng nhẹ), bạch cầu trung tính 74%.\nHồng cầu 4,3 T/L, Hb 128 g/L, tiểu cầu 265 G/L: trong giới hạn bình thường.'),
  ('XQ-NG', 'Chụp thẳng, hít sâu nín thở',
   'Hai phế trường sáng, không thấy tổn thương nhu mô. Bóng tim không to. Góc sườn hoành hai bên nhọn.')
) AS v(service, instruction, result)
JOIN clinic_service s ON s.service_code = v.service
JOIN appointment a ON a.booking_code = 'DEMQ7K2A'
JOIN clinical_encounter enc ON enc.appointment_id = a.id;

-- The bills: hers paid at the desk, her son's still open (payable online).
INSERT INTO invoice (customer_id, clinical_encounter_id, appointment_id, clinic_id, invoice_number,
                     subtotal, discount_amount, total_amount, status, issued_at, paid_at, created_at, version)
SELECT enc.customer_id, enc.id, a.id, enc.clinic_id, 'HD-DEMO-' || a.booking_code,
       v.total, 0, v.total, v.status, enc.ended_at,
       CASE WHEN v.is_paid THEN enc.ended_at + INTERVAL '10 minutes' END, enc.ended_at, 0
FROM (VALUES
  ('DEMQ7K2A', 450000, 'PAID',   TRUE),
  ('DEMQ7K2B', 150000, 'UNPAID', FALSE)
) AS v(code, total, status, is_paid)
JOIN appointment a ON a.booking_code = v.code
JOIN clinical_encounter enc ON enc.appointment_id = a.id;

INSERT INTO invoice_item (invoice_id, clinic_service_id, description, quantity, unit_price, amount, created_at, version)
SELECT i.id, s.id, s.name, 1, s.price, s.price, now(), 0
FROM invoice i
JOIN clinic_service s ON s.service_code IN ('KHAM', 'XN-CTM', 'XQ-NG')
WHERE i.invoice_number = 'HD-DEMO-DEMQ7K2A'
UNION ALL
SELECT i.id, s.id, s.name, 1, s.price, s.price, now(), 0
FROM invoice i
JOIN clinic_service s ON s.service_code = 'KHAM'
WHERE i.invoice_number = 'HD-DEMO-DEMQ7K2B';

INSERT INTO payment (invoice_id, amount, payment_method, status, paid_at, created_at, version)
SELECT i.id, i.total_amount, 'CASH', 'PAID', i.paid_at, i.paid_at, 0
FROM invoice i WHERE i.invoice_number = 'HD-DEMO-DEMQ7K2A';

--rollback DELETE FROM payment WHERE invoice_id IN (SELECT id FROM invoice WHERE invoice_number LIKE 'HD-DEMO-DEMQ7K2%');
--rollback DELETE FROM invoice_item WHERE invoice_id IN (SELECT id FROM invoice WHERE invoice_number LIKE 'HD-DEMO-DEMQ7K2%');
--rollback DELETE FROM invoice WHERE invoice_number LIKE 'HD-DEMO-DEMQ7K2%';
--rollback DELETE FROM service_order WHERE encounter_id IN (SELECT e.id FROM clinical_encounter e JOIN appointment a ON a.id = e.appointment_id WHERE a.booking_code LIKE 'DEMQ7K2%');
--rollback DELETE FROM clinical_encounter WHERE appointment_id IN (SELECT id FROM appointment WHERE booking_code LIKE 'DEMQ7K2%');
--rollback DELETE FROM appointment WHERE booking_code LIKE 'DEMQ7K2%';
--rollback DELETE FROM customer WHERE phone = '+84900000501' AND guardian_id IS NOT NULL;
--rollback DELETE FROM customer WHERE phone = '+84900000501';
--rollback DELETE FROM user_roles WHERE user_id = (SELECT id FROM users WHERE phone = '+84900000501');
--rollback DELETE FROM users WHERE phone = '+84900000501';
