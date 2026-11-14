--liquibase formatted sql

--changeset vitalcare:077-backfill-clinic-alert-rules
--comment Backfill clinics without clinic-wide rules. Reuse the existing 043/058 configuration, disabled pending clinical review.
-- Preserve all configured clinics, including disabled or deliberately deleted rules.
-- This runs once for clinics present at deployment; it does not configure future clinics.
INSERT INTO alert_rule
    (clinic_id, customer_id, name, metric, operator, threshold, severity, enabled, note, created_at, version)
SELECT c.clinic_id, NULL, r.name, r.metric, r.operator, r.threshold, r.severity, FALSE,
       'Cấu hình khởi tạo, chưa được xác nhận chuyên môn. Bác sĩ cần kiểm tra ngưỡng theo đối tượng bệnh nhân và thiết bị trước khi bật.',
       now(), 0
FROM clinic c
CROSS JOIN (VALUES
    ('SpO₂ thấp', 'SPO2', 'BELOW', 94.00, 'WARNING'),
    ('SpO₂ rất thấp', 'SPO2', 'BELOW', 90.00, 'CRITICAL'),
    ('Nhịp tim nhanh', 'HEART_RATE', 'ABOVE', 120.00, 'WARNING'),
    ('Nhịp tim rất nhanh', 'HEART_RATE', 'ABOVE', 140.00, 'CRITICAL'),
    ('Nhịp tim chậm', 'HEART_RATE', 'BELOW', 50.00, 'WARNING'),
    ('Nhịp tim rất chậm', 'HEART_RATE', 'BELOW', 40.00, 'CRITICAL'),
    ('Nhiệt độ cơ thể cao', 'BODY_TEMPERATURE', 'ABOVE', 37.50, 'WARNING'),
    ('Nhiệt độ cơ thể rất cao', 'BODY_TEMPERATURE', 'ABOVE', 38.50, 'CRITICAL')
) AS r(name, metric, operator, threshold, severity)
WHERE c.deleted_at IS NULL
  AND NOT EXISTS (
      SELECT 1 FROM alert_rule existing
      WHERE existing.clinic_id = c.clinic_id AND existing.customer_id IS NULL
  );
