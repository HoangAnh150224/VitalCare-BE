--liquibase formatted sql

--changeset vitalcare:079-demo-revenue-feedback-history
--comment Ninety days of sample bills, completed visits and patient feedback at every clinic, so the revenue report and the feedback screen have something to show on any environment. It brings its own walk-in patients (no accounts) and takes whatever services and doctors the clinic has, so it does not depend on earlier demo rows still being there. Everything is marked as sample data.

-- Twelve walk-in patients, without accounts: nobody's own history is touched.
CREATE TEMP TABLE demo_people ON COMMIT DROP AS
SELECT v.n, v.full_name, v.dob, v.gender, '+849000006' || lpad(v.n::text, 2, '0') AS phone
FROM (VALUES
  (1,  'Nguyễn Văn An',     DATE '1958-04-12', 'male'),
  (2,  'Trần Thị Bích',     DATE '1964-09-03', 'female'),
  (3,  'Lê Quang Dũng',     DATE '1971-01-25', 'male'),
  (4,  'Phạm Thị Hạnh',     DATE '1979-07-18', 'female'),
  (5,  'Hoàng Minh Đức',    DATE '1985-11-30', 'male'),
  (6,  'Vũ Thị Lan',        DATE '1990-02-14', 'female'),
  (7,  'Đặng Văn Hùng',     DATE '1955-06-08', 'male'),
  (8,  'Bùi Thị Ngọc',      DATE '1996-12-21', 'female'),
  (9,  'Đỗ Thanh Sơn',      DATE '1968-03-17', 'male'),
  (10, 'Ngô Thị Thu',       DATE '1974-08-09', 'female'),
  (11, 'Dương Văn Khải',    DATE '1982-05-27', 'male'),
  (12, 'Lý Thị Mai',        DATE '1961-10-05', 'female')
) AS v(n, full_name, dob, gender);

INSERT INTO customer (full_name, phone, date_of_birth, gender, province, ward, address, nationality, status,
                      patient_activated_at, patient_activation_source, created_at, version)
SELECT p.full_name, p.phone, p.dob, p.gender, 'Hà Nội', 'Phường Cầu Giấy', 'Dữ liệu mẫu', 'Việt Nam', 'PATIENT',
       now() - INTERVAL '90 days', 'CHECK_IN', now() - INTERVAL '90 days', 0
FROM demo_people p
WHERE NOT EXISTS (SELECT 1 FROM customer c WHERE c.phone = p.phone);

UPDATE customer SET customer_code = 'KH' || lpad(id::text, 6, '0')
WHERE phone LIKE '+849000006__' AND customer_code IS NULL;

CREATE TEMP TABLE demo_customer ON COMMIT DROP AS
SELECT p.n, min(c.id) AS id
FROM demo_people p JOIN customer c ON c.phone = p.phone AND c.deleted_at IS NULL
GROUP BY p.n;

-- What each clinic sells: number 0 is its examination fee, the rest follow.
CREATE TEMP TABLE demo_service ON COMMIT DROP AS
SELECT c.clinic_id, s.id, s.name, s.price, s.category,
       row_number() OVER (PARTITION BY c.clinic_id ORDER BY (s.category = 'CONSULTATION') DESC, s.id) - 1 AS n,
       count(*) OVER (PARTITION BY c.clinic_id) AS total
FROM clinic c
JOIN clinic_service s ON (s.clinic_id IS NULL OR s.clinic_id = c.clinic_id)
                     AND s.deleted_at IS NULL AND s.status = 'ACTIVE' AND s.price > 0
WHERE c.deleted_at IS NULL;

CREATE TEMP TABLE demo_doctor ON COMMIT DROP AS
SELECT e.clinic_id, e.id,
       row_number() OVER (PARTITION BY e.clinic_id ORDER BY e.id) - 1 AS n,
       count(*) OVER (PARTITION BY e.clinic_id) AS total
FROM employee e
WHERE e.staff_type = 'DOCTOR' AND e.status = 'ACTIVE' AND e.deleted_at IS NULL AND e.clinic_id IS NOT NULL;

-- Three to eight bills a working day (Monday to Saturday) over the ninety days before today.
-- h is a steady pseudo-random number per bill: every choice below is read off it.
CREATE TEMP TABLE demo_slot ON COMMIT DROP AS
SELECT c.clinic_id, d.day, k.k,
       'HD-MAU-' || left(c.clinic_id::text, 8) || '-' || to_char(d.day, 'YYMMDD') || '-' || k.k AS number,
       (hashtext(c.clinic_id::text || d.day::text || '#' || k.k)::bigint & 2147483647) AS h
FROM clinic c
CROSS JOIN LATERAL (SELECT (now() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date - g AS day
                    FROM generate_series(1, 90) AS g) AS d
CROSS JOIN generate_series(1, 8) AS k(k)
WHERE c.deleted_at IS NULL
  AND extract(isodow FROM d.day) <> 7
  AND k.k <= 3 + (hashtext(c.clinic_id::text || d.day::text)::bigint & 2147483647) % 6;

-- 45% examination fees, the rest spread over the other services. One examination in five is a
-- whole visit (appointment, encounter, bill), always paid. Of the others about one in eleven of
-- the last thirty days is still owed.
CREATE TEMP TABLE demo_bill ON COMMIT DROP AS
SELECT b.clinic_id, b.day, b.k, b.number, b.h, s.id AS service_id, s.name AS service_name, s.price,
       CASE WHEN s.category = 'CONSULTATION' THEN 'EXAM' ELSE 'SERVICE' END AS kind,
       p.id AS customer_id,
       (b.day + TIME '07:30' + (b.k * 55 + b.h % 40) * INTERVAL '1 minute')
           AT TIME ZONE 'Asia/Ho_Chi_Minh' AS issued_at,
       (s.category = 'CONSULTATION' AND b.h % 5 = 0) AS is_visit,
       CASE WHEN NOT (s.category = 'CONSULTATION' AND b.h % 5 = 0) AND b.h % 11 = 0
                 AND b.day >= (now() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date - 30
            THEN 'UNPAID' ELSE 'PAID' END AS status,
       CASE WHEN b.h % 3 = 0 THEN 'BANK_TRANSFER' ELSE 'CASH' END AS method,
       'M' || upper(substr(md5(b.number), 1, 9)) AS booking_code
FROM demo_slot b
JOIN demo_service s ON s.clinic_id = b.clinic_id
     AND s.n = CASE WHEN b.h % 100 < 45 THEN 0 ELSE 1 + (b.h / 100) % GREATEST(s.total - 1, 1) END
JOIN demo_customer p ON p.n = 1 + (b.h / 7) % 12
WHERE NOT EXISTS (SELECT 1 FROM invoice i WHERE i.invoice_number = b.number);

-- The whole visits: a completed appointment and what the doctor concluded.
INSERT INTO appointment (customer_id, employee_id, clinic_id, appointment_date, start_time, end_time, reason,
                         status, booking_code, checked_in_at, paid_at, created_at, version)
SELECT b.customer_id, d.id, b.clinic_id, b.day, TIME '08:00' + b.k * INTERVAL '45 minutes',
       TIME '08:30' + b.k * INTERVAL '45 minutes', v.complaint || ' (dữ liệu mẫu)',
       'COMPLETED', b.booking_code, b.issued_at, b.issued_at + INTERVAL '10 minutes',
       b.issued_at - INTERVAL '1 day', 0
FROM demo_bill b
LEFT JOIN demo_doctor d ON d.clinic_id = b.clinic_id AND d.n = (b.h / 13) % d.total
JOIN (VALUES
  (0, 'Tức ngực khi gắng sức'),
  (1, 'Ho kéo dài trên hai tuần'),
  (2, 'Hồi hộp, đánh trống ngực'),
  (3, 'Khó thở về đêm'),
  (4, 'Tái khám tăng huyết áp'),
  (5, 'Khò khè, tái khám hen phế quản')
) AS v(n, complaint) ON v.n = (b.h / 17) % 6
WHERE b.is_visit
  AND NOT EXISTS (SELECT 1 FROM appointment a WHERE a.booking_code = b.booking_code);

INSERT INTO clinical_encounter (appointment_id, customer_id, employee_id, clinic_id, started_at, ended_at,
                                chief_complaint, diagnosis, follow_up, status, created_at, version)
SELECT a.id, a.customer_id, a.employee_id, a.clinic_id, b.issued_at + INTERVAL '15 minutes',
       b.issued_at + INTERVAL '45 minutes', a.reason, v.diagnosis || ' (dữ liệu mẫu)', v.follow_up,
       'COMPLETED', b.issued_at + INTERVAL '15 minutes', 0
FROM demo_bill b
JOIN appointment a ON a.booking_code = b.booking_code
JOIN (VALUES
  (0, 'Cơn đau thắt ngực ổn định',        'Tái khám sau 4 tuần, mang theo kết quả điện tim.'),
  (1, 'Viêm phế quản mạn',                 'Tái khám sau 2 tuần nếu còn ho.'),
  (2, 'Ngoại tâm thu thất lành tính',      'Theo dõi Holter điện tim 24 giờ khi còn triệu chứng.'),
  (3, 'Suy tim độ II theo NYHA',           'Tái khám sau 2 tuần, theo dõi cân nặng mỗi ngày.'),
  (4, 'Tăng huyết áp nguyên phát',         'Đo huyết áp tại nhà sáng và tối, tái khám sau 1 tháng.'),
  (5, 'Hen phế quản kiểm soát một phần',   'Dùng thuốc dự phòng đều, tái khám sau 1 tháng.')
) AS v(n, diagnosis, follow_up) ON v.n = (b.h / 17) % 6
WHERE b.is_visit
  AND NOT EXISTS (SELECT 1 FROM clinical_encounter e WHERE e.appointment_id = a.id);

-- The bills, their one line each, and the money taken for the settled ones.
INSERT INTO invoice (customer_id, clinic_id, appointment_id, invoice_number, kind, subtotal, discount_amount,
                     total_amount, status, issued_at, paid_at, created_at, version)
SELECT b.customer_id, b.clinic_id, a.id, b.number, b.kind, b.price, 0, b.price, b.status, b.issued_at,
       CASE WHEN b.status = 'PAID' THEN b.issued_at + INTERVAL '10 minutes' END, b.issued_at, 0
FROM demo_bill b
LEFT JOIN appointment a ON b.is_visit AND a.booking_code = b.booking_code;

INSERT INTO invoice_item (invoice_id, clinic_service_id, description, quantity, unit_price, amount, created_at, version)
SELECT i.id, b.service_id, b.service_name || ' (dữ liệu mẫu)', 1, b.price, b.price, b.issued_at, 0
FROM demo_bill b JOIN invoice i ON i.invoice_number = b.number;

INSERT INTO payment (invoice_id, amount, payment_method, status, paid_at, created_at, version)
SELECT i.id, i.total_amount, b.method, 'PAID', i.paid_at, i.paid_at, 0
FROM demo_bill b JOIN invoice i ON i.invoice_number = b.number
WHERE b.status = 'PAID';

-- What three patients in five said the day after: mostly pleased, a few not.
INSERT INTO patient_feedback (appointment_id, customer_id, clinic_id, rating, comment, created_at, version)
SELECT a.id, a.customer_id, a.clinic_id, r.rating, '[Dữ liệu mẫu] ' || t.comment, b.issued_at + INTERVAL '1 day', 0
FROM demo_bill b
JOIN appointment a ON a.booking_code = b.booking_code
JOIN (VALUES (0, 5), (1, 5), (2, 4), (3, 5), (4, 4), (5, 3), (6, 5), (7, 4), (8, 2), (9, 5), (10, 4), (11, 1)
     ) AS r(n, rating) ON r.n = (b.h / 25) % 12
JOIN (VALUES
  (5, 0, 'Bác sĩ khám kỹ, giải thích rõ tình trạng tim mạch và cách dùng thuốc. Rất hài lòng.'),
  (5, 1, 'Được hướng dẫn đeo thiết bị theo dõi tại nhà rất cẩn thận. Nhân viên nhiệt tình.'),
  (5, 2, 'Thủ tục nhanh, không phải chờ lâu. Phòng khám sạch sẽ.'),
  (4, 0, 'Bác sĩ tư vấn tận tình. Mong có thêm chỗ ngồi chờ ở khu xét nghiệm.'),
  (4, 1, 'Khám tốt, kết quả trả đúng hẹn. Bãi gửi xe hơi chật.'),
  (4, 2, 'Hài lòng với buổi khám. Mong phòng khám nhắn tin nhắc lịch tái khám.'),
  (3, 0, 'Chất lượng khám ổn nhưng chờ đo điện tim hơi lâu.'),
  (3, 1, 'Bác sĩ nhiệt tình, nhưng quầy thanh toán chỉ có một người nên phải chờ.'),
  (3, 2, 'Tạm ổn. Mong được giải thích kỹ hơn về kết quả xét nghiệm.'),
  (2, 0, 'Chờ gần một tiếng mới đến lượt dù đã đăng ký trước.'),
  (2, 1, 'Chưa được hướng dẫn rõ quy trình nhận kết quả, phải hỏi nhiều lần.'),
  (2, 2, 'Phòng chờ đông và ồn, khó nghe gọi số.'),
  (1, 0, 'Lịch khám bị dời mà không được báo trước.'),
  (1, 1, 'Phải quay lại lần hai mới lấy được kết quả.'),
  (1, 2, 'Thời gian chờ quá lâu, không ai giải thích lý do.')
) AS t(rating, n, comment) ON t.rating = r.rating AND t.n = (b.h / 300) % 3
WHERE b.is_visit AND (b.h / 5) % 5 < 3
  AND NOT EXISTS (SELECT 1 FROM patient_feedback f WHERE f.appointment_id = a.id);
