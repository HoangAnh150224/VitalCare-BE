--liquibase formatted sql

--changeset vitalcare:035-seed-transfer-test-bills
--comment Two small unpaid bills on the demo patient (0900000501) for trying a real bank transfer through VietQR + SePay: 2 000 ₫ to pay in full, 10 000 ₫ to try a short transfer first.

-- Two concluded visits with BS. Nguyễn Minh Khoa yesterday, each with its
-- encounter and an unpaid bill. The amounts are deliberately tiny: they are
-- paid with real money, into the clinic's own account.

INSERT INTO appointment (customer_id, employee_id, clinic_id, appointment_date, start_time, end_time, reason,
                         status, booking_code, queue_number, checked_in_at, created_at, version)
SELECT self.id, e.id, e.clinic_id, CURRENT_DATE - 1, v.starts, v.starts + INTERVAL '15 minutes', v.reason,
       'COMPLETED', v.code, v.queue, (CURRENT_DATE - 1) + v.starts - INTERVAL '10 minutes', now(), 0
FROM (VALUES
  ('DEMQ7K2E', TIME '14:00', 6, 'Thử thanh toán chuyển khoản (đủ tiền)'),
  ('DEMQ7K2F', TIME '14:15', 7, 'Thử thanh toán chuyển khoản (thiếu rồi bù)')
) AS v(code, starts, queue, reason)
JOIN users owner ON owner.phone = '+84900000501'
JOIN customer self ON self.user_id = owner.id
JOIN employee e ON e.user_id = (SELECT id FROM users WHERE phone = '+84900000011');

INSERT INTO clinical_encounter (appointment_id, customer_id, employee_id, clinic_id, started_at, ended_at,
                                chief_complaint, examination_findings, diagnosis, follow_up,
                                status, created_at, version)
SELECT a.id, a.customer_id, a.employee_id, a.clinic_id,
       a.appointment_date + a.start_time, a.appointment_date + a.start_time + INTERVAL '10 minutes',
       'Tái khám, kiểm tra lại sau đợt viêm họng.', 'Họng hết đỏ, amidan không sưng. Phổi trong.',
       'Viêm họng cấp đã ổn định', 'Không cần tái khám nếu không còn triệu chứng.',
       'COMPLETED', now(), 0
FROM appointment a WHERE a.booking_code IN ('DEMQ7K2E', 'DEMQ7K2F');

INSERT INTO invoice (customer_id, clinical_encounter_id, appointment_id, clinic_id, invoice_number,
                     subtotal, discount_amount, total_amount, status, issued_at, created_at, version)
SELECT enc.customer_id, enc.id, a.id, enc.clinic_id, 'HD-DEMO-' || a.booking_code,
       v.total, 0, v.total, 'UNPAID', enc.ended_at, enc.ended_at, 0
FROM (VALUES ('DEMQ7K2E', 2000), ('DEMQ7K2F', 10000)) AS v(code, total)
JOIN appointment a ON a.booking_code = v.code
JOIN clinical_encounter enc ON enc.appointment_id = a.id;

INSERT INTO invoice_item (invoice_id, clinic_service_id, description, quantity, unit_price, amount, created_at, version)
SELECT i.id, s.id, 'Tái khám (phí thử chuyển khoản)', 1, i.total_amount, i.total_amount, now(), 0
FROM invoice i
JOIN clinic_service s ON s.service_code = 'KHAM'
WHERE i.invoice_number IN ('HD-DEMO-DEMQ7K2E', 'HD-DEMO-DEMQ7K2F');

--rollback DELETE FROM payment WHERE invoice_id IN (SELECT id FROM invoice WHERE invoice_number IN ('HD-DEMO-DEMQ7K2E', 'HD-DEMO-DEMQ7K2F'));
--rollback DELETE FROM invoice_item WHERE invoice_id IN (SELECT id FROM invoice WHERE invoice_number IN ('HD-DEMO-DEMQ7K2E', 'HD-DEMO-DEMQ7K2F'));
--rollback DELETE FROM invoice WHERE invoice_number IN ('HD-DEMO-DEMQ7K2E', 'HD-DEMO-DEMQ7K2F');
--rollback DELETE FROM clinical_encounter WHERE appointment_id IN (SELECT id FROM appointment WHERE booking_code IN ('DEMQ7K2E', 'DEMQ7K2F'));
--rollback DELETE FROM appointment WHERE booking_code IN ('DEMQ7K2E', 'DEMQ7K2F');
