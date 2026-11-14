--liquibase formatted sql

--changeset vitalcare:078-demo-patient-feedback
--comment Sample feedback for existing completed demo visits only. Preserve existing feedback, including deleted records.
INSERT INTO patient_feedback
    (appointment_id, customer_id, clinic_id, rating, comment, created_at, version)
SELECT a.id, a.customer_id, a.clinic_id, sample.rating, sample.comment,
       (a.appointment_date + COALESCE(a.end_time, TIME '17:00') + INTERVAL '1 hour')
           AT TIME ZONE 'Asia/Ho_Chi_Minh', 0
FROM (VALUES
    ('DEMQ7K2A', 5, '[Dữ liệu mẫu] Bác sĩ giải thích rõ kết quả khám, hướng dẫn theo dõi sức khỏe dễ hiểu. Nhân viên hỗ trợ nhiệt tình.'),
    ('DEMQ7K2B', 4, '[Dữ liệu mẫu] Phòng khám sạch sẽ, bác sĩ tư vấn cẩn thận. Mong có thêm hướng dẫn chuẩn bị trước buổi khám.'),
    ('DEMQ7K2E', 3, '[Dữ liệu mẫu] Chất lượng tư vấn tốt nhưng thời gian chờ hơi lâu. Mong phòng khám thông báo khi lịch khám thay đổi.'),
    ('DEMQ7K2F', 2, '[Dữ liệu mẫu] Tôi chưa được hướng dẫn rõ quy trình thanh toán và nhận kết quả. Mong bổ sung bảng hướng dẫn tại quầy.')
) AS sample(booking_code, rating, comment)
JOIN appointment a ON a.booking_code = sample.booking_code
JOIN customer c ON c.id = a.customer_id AND c.deleted_at IS NULL
JOIN clinic cl ON cl.clinic_id = a.clinic_id AND cl.deleted_at IS NULL
WHERE a.deleted_at IS NULL AND a.status = 'COMPLETED'
  AND a.appointment_date < (now() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date
  AND NOT EXISTS (SELECT 1 FROM patient_feedback f WHERE f.appointment_id = a.id);
