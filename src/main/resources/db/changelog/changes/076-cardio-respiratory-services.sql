--liquibase formatted sql

--changeset vitalcare:076-cardio-respiratory-services
--comment The catalogue for a clinic that sees Tim mạch and Hô hấp. The entries 032 seeded keep their codes (bills and orders point at them); the one that fits neither specialty is retired, and the investigations the two specialties order are added. A code already in use is left alone. Prices are placeholders for the clinic to set.

-- Still the only CONSULTATION entry: the examination fee is the first active one.
UPDATE clinic_service
SET name = 'Khám chuyên khoa Tim mạch - Hô hấp',
    description = 'Phí khám của một lần khám ngoại trú chuyên khoa Tim mạch hoặc Hô hấp.',
    updated_at = now()
WHERE service_code = 'KHAM' AND name = 'Khám bệnh' AND deleted_at IS NULL;

-- Abdominal ultrasound: withdrawn where nothing points at it, otherwise only stopped being offered.
UPDATE clinic_service s
SET status = 'INACTIVE', updated_at = now(),
    deleted_at = CASE WHEN EXISTS (SELECT 1 FROM service_order o WHERE o.service_id = s.id)
                        OR EXISTS (SELECT 1 FROM invoice_item i WHERE i.clinic_service_id = s.id)
                      THEN NULL ELSE now() END
WHERE s.service_code = 'SA-BUNG' AND s.deleted_at IS NULL;

INSERT INTO clinic_service (service_code, name, description, price, status, category, turnaround_minutes, created_at, version)
SELECT v.code, v.name, v.description, v.price, 'ACTIVE', v.category, v.minutes, now(), 0
FROM (VALUES
  -- Tim mạch
  ('SA-TIM',     'Siêu âm tim Doppler màu',            'Đánh giá cấu trúc và chức năng tim qua thành ngực.',              350000,  'IMAGING',   30),
  ('SA-MC',      'Siêu âm Doppler mạch cảnh',          'Khảo sát động mạch cảnh và đốt sống hai bên.',                    300000,  'IMAGING',   30),
  ('DTD-GS',     'Điện tim gắng sức',                  'Nghiệm pháp gắng sức trên thảm chạy có theo dõi điện tim.',       450000,  'PROCEDURE', 45),
  ('HOLTER-DTD', 'Holter điện tim 24 giờ',             'Ghi điện tim liên tục 24 giờ; trả kết quả sau khi tháo máy.',     600000,  'PROCEDURE', 1440),
  ('HOLTER-HA',  'Holter huyết áp 24 giờ',             'Đo huyết áp lưu động 24 giờ; trả kết quả sau khi tháo máy.',      500000,  'PROCEDURE', 1440),
  ('XN-MM',      'Bộ mỡ máu',                          'Cholesterol toàn phần, HDL-C, LDL-C và triglycerid.',             160000,  'LAB',       60),
  ('XN-TROP',    'Troponin T độ nhạy cao',             'Định lượng troponin T hs trong chẩn đoán tổn thương cơ tim.',     250000,  'LAB',       60),
  ('XN-BNP',     'NT-proBNP',                          'Định lượng NT-proBNP trong chẩn đoán và theo dõi suy tim.',       550000,  'LAB',       90),
  -- Hô hấp
  ('HH-KY',      'Đo chức năng hô hấp',                'Hô hấp ký: FVC, FEV1 và tỉ số FEV1/FVC.',                         250000,  'PROCEDURE', 30),
  ('HH-KY-GPQ',  'Hô hấp ký có test giãn phế quản',    'Đo chức năng hô hấp trước và sau khi dùng thuốc giãn phế quản.',  350000,  'PROCEDURE', 45),
  ('KD-PQ',      'Khí dung thuốc giãn phế quản',       'Một lần khí dung tại phòng khám.',                                80000,   'PROCEDURE', 20),
  ('CT-NGUC',    'CT ngực không cản quang',            'Chụp cắt lớp vi tính lồng ngực không tiêm thuốc cản quang.',      1200000, 'IMAGING',   60),
  ('XN-KM',      'Khí máu động mạch',                  'pH, PaO2, PaCO2 và HCO3 máu động mạch.',                          300000,  'LAB',       45),
  ('XN-CRP',     'CRP định lượng',                     'Định lượng protein phản ứng C trong đánh giá viêm, nhiễm trùng.', 120000,  'LAB',       60)
) AS v(code, name, description, price, category, minutes)
WHERE NOT EXISTS (SELECT 1 FROM clinic_service s
                  WHERE lower(s.service_code) = lower(v.code) AND s.deleted_at IS NULL);

--rollback -- Left in place: orders and bills may point at these entries by then.
