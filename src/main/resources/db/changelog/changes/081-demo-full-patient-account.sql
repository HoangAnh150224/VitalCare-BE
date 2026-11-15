--liquibase formatted sql

--changeset vitalcare:081-demo-full-patient-account splitStatements:false
--comment One patient account with something on every screen of the patient app, for demos on any environment: a complete profile and medical record, two relatives, finished visits with their results and bills (one still owed), a cancelled and an upcoming visit, a worn device with thirty days of readings and the alerts they raised, monitoring by a doctor, a chat with the clinic, notifications, and sharing both ways. Phone 0911000010, password VitalCareTest#2026 (the hash 050 and 065 use). Skipped entirely when the number is already taken, so nobody's account is touched.

DO $demo$
DECLARE
    v_phone   CONSTANT varchar := '+84911000010';
    v_hash    CONSTANT varchar := '$2a$10$IthJ0J/8bj.DX595lw6Doewym8FL6aUGRfXLHLWNrZ7QdIeLtk1Eu';
    v_tz      CONSTANT text    := 'Asia/Ho_Chi_Minh';
    d0        date := (now() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date;
    v_clinic  uuid;
    v_doctor  bigint;
    v_doc_uid bigint;
    v_user    bigint;
    v_me      bigint;
    v_wife    bigint;
    v_mom     bigint;
    v_consult record;
    v_svc     record;
    v_visit   record;
    v_day     date;
    v_at      timestamptz;
    v_appt    bigint;
    v_enc     bigint;
    v_order   bigint;
    v_inv     bigint;
    v_inv_no  int := 0;
    v_enc_v2  bigint;
    v_dev_me  bigint;
    v_dev_mom bigint;
    v_asg_me  bigint;
    v_asg_mom bigint;
    v_conv    bigint;
    v_vital   bigint;
    v_alert   bigint;
    v_up      bigint;
    v_unpaid  boolean;
    i         int;
BEGIN
    IF EXISTS (SELECT 1 FROM users WHERE phone = v_phone) THEN
        RAISE NOTICE '081: % is already taken, nothing seeded', v_phone;
        RETURN;
    END IF;

    -- The clinic: VitalCare Clinic (cơ sở 1) when it exists, otherwise the first one still open.
    SELECT clinic_id INTO v_clinic FROM clinic
    WHERE deleted_at IS NULL
    ORDER BY (clinic_id = '5b1f8c2e-6a3d-4f7e-9c41-2d8e0a7b3c19') DESC, created_at
    LIMIT 1;
    IF v_clinic IS NULL THEN
        RAISE NOTICE '081: no clinic, nothing seeded';
        RETURN;
    END IF;

    -- The doctor: the test doctor (0911000003) when at this clinic, otherwise its first active doctor.
    SELECT e.id, e.user_id INTO v_doctor, v_doc_uid
    FROM employee e JOIN users u ON u.id = e.user_id
    WHERE e.clinic_id = v_clinic AND e.staff_type = 'DOCTOR' AND e.status = 'ACTIVE' AND e.deleted_at IS NULL
    ORDER BY (u.phone = '+84911000003') DESC, e.id
    LIMIT 1;

    SELECT id, name, price INTO v_consult FROM clinic_service
    WHERE (clinic_id IS NULL OR clinic_id = v_clinic) AND category = 'CONSULTATION'
      AND status = 'ACTIVE' AND deleted_at IS NULL
    ORDER BY clinic_id NULLS LAST, id LIMIT 1;

    -- ===================================================================== account and profiles
    INSERT INTO users (phone, password_hash, full_name, status, created_at)
    VALUES (v_phone, v_hash, 'Nguyễn Văn Bình', 'ACTIVE', now() - INTERVAL '150 days')
    RETURNING id INTO v_user;

    INSERT INTO user_roles (user_id, role_id) SELECT v_user, id FROM roles WHERE code = 'PATIENT';

    INSERT INTO customer (user_id, full_name, phone, date_of_birth, gender, national_id, national_id_type,
                          national_id_issued_on, province, ward, address, ethnicity, nationality, occupation,
                          emergency_contact_name, emergency_contact_phone, status, patient_activated_at,
                          patient_activation_source, created_at, version)
    VALUES (v_user, 'Nguyễn Văn Bình', v_phone, DATE '1968-03-14', 'male', '001068012345', 'cccd',
            DATE '2021-06-10', 'Hà Nội', 'Phường Cầu Giấy', 'Số 12 ngõ 68 Trần Thái Tông', 'Kinh', 'Việt Nam',
            'Kỹ sư xây dựng', 'Lê Thị Hoa', '+84911000011', 'PATIENT', now() - INTERVAL '120 days', 'CHECK_IN',
            now() - INTERVAL '150 days', 0)
    RETURNING id INTO v_me;

    -- Two relatives, the most an account may hold.
    INSERT INTO customer (guardian_id, full_name, phone, relationship, date_of_birth, gender, national_id,
                          national_id_type, national_id_issued_on, province, ward, address, ethnicity, nationality,
                          occupation, status, created_at, version)
    VALUES (v_me, 'Lê Thị Hoa', '+84911000011', 'spouse', DATE '1971-09-22', 'female', '001171023456', 'cccd',
            DATE '2021-07-02', 'Hà Nội', 'Phường Cầu Giấy', 'Số 12 ngõ 68 Trần Thái Tông', 'Kinh', 'Việt Nam',
            'Giáo viên', 'NEUTRAL', now() - INTERVAL '140 days', 0)
    RETURNING id INTO v_wife;

    INSERT INTO customer (guardian_id, full_name, phone, relationship, date_of_birth, gender, national_id,
                          national_id_type, national_id_issued_on, province, ward, address, ethnicity, nationality,
                          occupation, status, patient_activated_at, patient_activation_source, created_at, version)
    VALUES (v_me, 'Nguyễn Thị Lành', NULL, 'parent', DATE '1944-11-05', 'female', '001144004567', 'cccd',
            DATE '2021-05-18', 'Hà Nội', 'Phường Cầu Giấy', 'Số 12 ngõ 68 Trần Thái Tông', 'Kinh', 'Việt Nam',
            'Hưu trí', 'PATIENT', now() - INTERVAL '20 days', 'CHECK_IN', now() - INTERVAL '140 days', 0)
    RETURNING id INTO v_mom;

    UPDATE customer SET customer_code = 'KH' || lpad(id::text, 6, '0')
    WHERE id IN (v_me, v_wife, v_mom) AND customer_code IS NULL;

    INSERT INTO medical_record (customer_id, blood_type, allergies, medical_history, family_history,
                                current_medications, note, created_at, version)
    VALUES
      (v_me, 'O+', 'Dị ứng Penicillin (nổi mẩn đỏ)', 'Tăng huyết áp độ 1 phát hiện năm 2022. Rối loạn mỡ máu.',
       'Bố mất do nhồi máu cơ tim năm 68 tuổi. Mẹ bệnh phổi tắc nghẽn mạn tính.',
       'Amlodipin 5mg x 1 viên sáng; Atorvastatin 20mg x 1 viên tối', 'Không hút thuốc, uống rượu bia ít.',
       now() - INTERVAL '120 days', 0),
      (v_mom, 'A+', 'Chưa ghi nhận', 'Bệnh phổi tắc nghẽn mạn tính (COPD) từ 2015. Thoái hóa khớp gối.',
       'Không rõ', 'Salmeterol/Fluticasone hít 2 lần/ngày', 'Đi lại chậm, cần người đi kèm khi khám.',
       now() - INTERVAL '20 days', 0),
      (v_wife, 'B+', 'Chưa ghi nhận', 'Khỏe mạnh', 'Mẹ đái tháo đường type 2', 'Không', NULL,
       now() - INTERVAL '140 days', 0);

    -- ===================================================================== finished visits
    -- Each: appointment, the doctor's encounter, the services ordered with their reviewed results,
    -- an exam bill and one bill per service. Visits never land on a Sunday.
    FOR v_visit IN
        SELECT * FROM (VALUES
          (1, v_me, 120, TIME '08:30',
           'Đau đầu, chóng mặt buổi sáng',
           'Tỉnh, tiếp xúc tốt. Tim đều, không tiếng thổi. Phổi không rale. Không phù.',
           'Tăng huyết áp độ 1; rối loạn lipid máu',
           'Tăng huyết áp độ 1 kèm rối loạn lipid máu. Bắt đầu Amlodipin 5mg và Atorvastatin 20mg, ăn giảm muối, tập thể dục 30 phút mỗi ngày.',
           'Tái khám sau 1 tháng, đo huyết áp tại nhà sáng và tối.',
           36.6, 82, 16, 152, 94, 98, 74.5, 168.0,
           ARRAY['Điện tim', 'Công thức máu', 'Bộ mỡ máu', 'Đường huyết'],
           ARRAY['Nhịp xoang đều, tần số 80 lần/phút. Trục trung gian. Không thấy dấu hiệu thiếu máu cơ tim cấp.',
                 'Hồng cầu 4,8 T/L; Hemoglobin 145 g/L; Bạch cầu 7,2 G/L; Tiểu cầu 250 G/L. Trong giới hạn bình thường.',
                 'Cholesterol toàn phần 6,4 mmol/L (tăng); LDL-C 4,1 mmol/L (tăng); HDL-C 1,0 mmol/L; Triglycerid 2,3 mmol/L (tăng).',
                 'Glucose máu lúc đói 5,6 mmol/L. Trong giới hạn bình thường.'],
           5, 'Bác sĩ khám kỹ, giải thích rõ về huyết áp và cách dùng thuốc. Rất hài lòng.', FALSE),
          (2, v_me, 45, TIME '09:00',
           'Tức ngực khi leo cầu thang, hồi hộp',
           'Tim đều, T1 T2 rõ. Phổi thông khí tốt. Huyết áp còn cao so với mục tiêu.',
           'Đau thắt ngực ổn định nghi ngờ; tăng huyết áp độ 1',
           'Siêu âm tim chức năng tâm thu bảo tồn, Troponin âm tính. Holter ghi nhận vài ngoại tâm thu thất lành tính. Đeo thiết bị theo dõi tại nhà 60 ngày để theo dõi nhịp tim, SpO2 và nhiệt độ.',
           'Tái khám sau 6 tuần hoặc ngay khi đau ngực kéo dài trên 15 phút.',
           36.7, 88, 17, 146, 92, 97, 74.0, 168.0,
           ARRAY['Siêu âm tim Doppler màu', 'Troponin T độ nhạy cao', 'Holter điện tim 24 giờ', 'X-quang ngực thẳng'],
           ARRAY['Các buồng tim không giãn. EF (Simpson) 62%. Không rối loạn vận động vùng. Hở van hai lá nhẹ.',
                 'hs-Troponin T 8 ng/L (bình thường < 14 ng/L). Âm tính.',
                 'Nhịp xoang, tần số trung bình 76 lần/phút (52–128). 312 ngoại tâm thu thất đơn lẻ, không có cơn nhịp nhanh.',
                 'Bóng tim không to. Hai phế trường sáng, không thấy tổn thương nhu mô. Góc sườn hoành nhọn.'],
           4, 'Khám tốt, được hướng dẫn đeo thiết bị theo dõi tại nhà rất cẩn thận. Chờ làm Holter hơi lâu.', FALSE),
          (3, v_mom, 20, TIME '08:00',
           'Ho khan, khó thở khi gắng sức',
           'Lồng ngực hình thùng, rì rào phế nang giảm hai đáy, ran rít rải rác. SpO2 khí trời 93%.',
           'Bệnh phổi tắc nghẽn mạn tính (COPD) đợt cấp nhẹ',
           'COPD đợt cấp nhẹ, đáp ứng tốt với khí dung. Tiếp tục thuốc hít duy trì, thêm khí dung khi khó thở. Đeo thiết bị theo dõi SpO2 tại nhà.',
           'Tái khám sau 2 tuần; đến ngay nếu SpO2 dưới 90% hoặc khó thở tăng.',
           37.2, 94, 22, 138, 80, 93, 52.0, 150.0,
           ARRAY['Đo chức năng hô hấp', 'X-quang ngực thẳng', 'Khí dung thuốc giãn phế quản'],
           ARRAY['FEV1/FVC 0,58; FEV1 52% dự đoán. Rối loạn thông khí tắc nghẽn mức độ trung bình (GOLD 2).',
                 'Hai phế trường tăng sáng, cơ hoành dẹt. Không thấy tổn thương viêm phổi.',
                 'Đã khí dung Salbutamol 5mg. Sau 20 phút SpO2 tăng lên 96%, giảm khó thở.'],
           5, 'Bác sĩ nhẹ nhàng với người già, nhân viên hỗ trợ đưa mẹ tôi đi làm xét nghiệm. Cảm ơn phòng khám.', FALSE),
          (4, v_me, 7, TIME '14:00',
           'Tái khám tăng huyết áp, theo dõi tim mạch',
           'Tim đều 72 lần/phút. Huyết áp đạt mục tiêu. Không phù chân.',
           'Tăng huyết áp độ 1 đã kiểm soát; ngoại tâm thu thất lành tính',
           'Huyết áp kiểm soát tốt với Amlodipin 5mg. Dữ liệu thiết bị tại nhà ổn định, chỉ vài lần nhịp nhanh khi gắng sức. Tiếp tục thuốc hiện tại.',
           'Tái khám sau 3 tháng, xét nghiệm lại mỡ máu.',
           36.5, 72, 16, 128, 82, 98, 72.5, 168.0,
           ARRAY['NT-proBNP', 'Holter huyết áp 24 giờ'],
           ARRAY['NT-proBNP 64 pg/mL (bình thường < 125 pg/mL). Không gợi ý suy tim.',
                 'Huyết áp trung bình 24 giờ 126/80 mmHg; ban ngày 130/83, ban đêm 117/72. Có trũng huyết áp ban đêm.'],
           NULL, NULL, TRUE)
        ) AS v(n, customer_id, days_ago, start_time, reason, findings, diagnosis, conclusion, follow_up,
               temperature, pulse, rr, sys, dia, spo2, weight, height, services, results,
               rating, feedback, last_unpaid)
        ORDER BY n
    LOOP
        v_day := d0 - v_visit.days_ago;
        IF extract(isodow FROM v_day) = 7 THEN v_day := v_day - 1; END IF;
        v_at := (v_day + v_visit.start_time) AT TIME ZONE v_tz;

        INSERT INTO appointment (customer_id, employee_id, clinic_id, appointment_date, start_time, end_time, reason,
                                 status, booking_code, checked_in_at, paid_at, created_at, version)
        VALUES (v_visit.customer_id, v_doctor, v_clinic, v_day, v_visit.start_time,
                v_visit.start_time + INTERVAL '30 minutes', v_visit.reason, 'COMPLETED',
                'D' || upper(substr(md5('081-demo-full-visit-' || v_visit.n), 1, 9)),
                v_at - INTERVAL '20 minutes', v_at - INTERVAL '15 minutes', v_at - INTERVAL '3 days', 0)
        RETURNING id INTO v_appt;

        INSERT INTO clinical_encounter (appointment_id, customer_id, employee_id, clinic_id, started_at, ended_at,
                                        chief_complaint, examination_findings, diagnosis, final_conclusion, follow_up,
                                        clinical_note, consultation_notes, consulted_at, consulted_by_id,
                                        temperature, pulse, respiratory_rate, systolic_bp, diastolic_bp, spo2,
                                        weight_kg, height_cm, status, created_at, version)
        VALUES (v_appt, v_visit.customer_id, v_doctor, v_clinic, v_at, v_at + INTERVAL '3 hours',
                v_visit.reason, v_visit.findings, v_visit.diagnosis, v_visit.conclusion, v_visit.follow_up,
                'Đã giải thích kết quả và hướng điều trị cho người bệnh.',
                'Đã tư vấn kết quả cận lâm sàng, người bệnh hiểu và đồng ý phác đồ.',
                v_at + INTERVAL '3 hours', v_doc_uid,
                v_visit.temperature, v_visit.pulse, v_visit.rr, v_visit.sys, v_visit.dia, v_visit.spo2,
                v_visit.weight, v_visit.height, 'COMPLETED', v_at, 0)
        RETURNING id INTO v_enc;
        IF v_visit.n = 2 THEN v_enc_v2 := v_enc; END IF;

        -- The examination fee, paid when booking.
        IF v_consult.id IS NOT NULL THEN
            v_inv_no := v_inv_no + 1;
            INSERT INTO invoice (customer_id, clinic_id, appointment_id, clinical_encounter_id, invoice_number, kind,
                                 subtotal, discount_amount, total_amount, status, issued_at, paid_at, created_at, version)
            VALUES (v_visit.customer_id, v_clinic, v_appt, v_enc, 'HD-DEMO-BN-' || lpad(v_inv_no::text, 3, '0'),
                    'EXAM', v_consult.price, 0, v_consult.price, 'PAID', v_at - INTERVAL '20 minutes',
                    v_at - INTERVAL '15 minutes', v_at - INTERVAL '20 minutes', 0)
            RETURNING id INTO v_inv;
            INSERT INTO invoice_item (invoice_id, clinic_service_id, description, quantity, unit_price, amount, created_at, version)
            VALUES (v_inv, v_consult.id, v_consult.name, 1, v_consult.price, v_consult.price, v_at - INTERVAL '20 minutes', 0);
            INSERT INTO payment (invoice_id, amount, payment_method, status, transaction_code, paid_at, created_at, version)
            VALUES (v_inv, v_consult.price, 'VNPAY', 'PAID', 'DEMO' || v_inv, v_at - INTERVAL '15 minutes',
                    v_at - INTERVAL '15 minutes', 0);
        END IF;

        -- The services, each with its result read and reviewed by the doctor, and its own bill.
        FOR i IN 1 .. array_length(v_visit.services, 1) LOOP
            SELECT id, name, price INTO v_svc FROM clinic_service
            WHERE name = v_visit.services[i] AND (clinic_id IS NULL OR clinic_id = v_clinic) AND deleted_at IS NULL
            ORDER BY clinic_id NULLS LAST, id LIMIT 1;
            CONTINUE WHEN v_svc.id IS NULL;

            INSERT INTO service_order (encounter_id, customer_id, clinic_id, service_id, unit_price, instruction, status,
                                       expected_result_at, performed_by_id, started_at, result_text, result_at,
                                       reviewed_by_id, reviewed_at, created_at, version)
            VALUES (v_enc, v_visit.customer_id, v_clinic, v_svc.id, v_svc.price, 'Theo chỉ định của bác sĩ', 'REVIEWED',
                    v_at + INTERVAL '2 hours', v_doc_uid, v_at + (20 + i * 15) * INTERVAL '1 minute',
                    v_visit.results[i], v_at + (60 + i * 20) * INTERVAL '1 minute',
                    v_doc_uid, v_at + INTERVAL '170 minutes', v_at + INTERVAL '15 minutes', 0)
            RETURNING id INTO v_order;

            -- The newest visit's first service is still owed, so the payment screen has a bill to pay.
            v_unpaid := v_visit.last_unpaid AND i = 1;
            v_inv_no := v_inv_no + 1;
            INSERT INTO invoice (customer_id, clinic_id, appointment_id, clinical_encounter_id, service_order_id,
                                 invoice_number, kind, subtotal, discount_amount, total_amount, status, issued_at,
                                 due_at, paid_at, created_at, version)
            VALUES (v_visit.customer_id, v_clinic, v_appt, v_enc, v_order,
                    'HD-DEMO-BN-' || lpad(v_inv_no::text, 3, '0'), 'SERVICE', v_svc.price, 0, v_svc.price,
                    CASE WHEN v_unpaid THEN 'UNPAID' ELSE 'PAID' END, v_at + INTERVAL '15 minutes',
                    CASE WHEN v_unpaid THEN v_at + INTERVAL '30 days' END,
                    CASE WHEN v_unpaid THEN NULL ELSE v_at + INTERVAL '18 minutes' END,
                    v_at + INTERVAL '15 minutes', 0)
            RETURNING id INTO v_inv;
            INSERT INTO invoice_item (invoice_id, clinic_service_id, description, quantity, unit_price, amount, created_at, version)
            VALUES (v_inv, v_svc.id, v_svc.name, 1, v_svc.price, v_svc.price, v_at + INTERVAL '15 minutes', 0);
            IF NOT v_unpaid THEN
                INSERT INTO payment (invoice_id, amount, payment_method, status, transaction_code, paid_at, created_at, version)
                VALUES (v_inv, v_svc.price, CASE WHEN i % 2 = 0 THEN 'CASH' ELSE 'BANK_TRANSFER' END, 'PAID',
                        'DEMO' || v_inv, v_at + INTERVAL '18 minutes', v_at + INTERVAL '18 minutes', 0);
            END IF;
        END LOOP;

        IF v_visit.rating IS NOT NULL THEN
            INSERT INTO patient_feedback (appointment_id, customer_id, clinic_id, rating, comment, reply, replied_at,
                                          created_at, version)
            VALUES (v_appt, v_visit.customer_id, v_clinic, v_visit.rating, v_visit.feedback,
                    'Cảm ơn anh/chị đã tin tưởng VitalCare. Chúc gia đình nhiều sức khỏe!',
                    v_at + INTERVAL '2 days', v_at + INTERVAL '1 day', 0);
        END IF;

        -- The cardiac visit: the patient came back three days later for the Holter reading.
        IF v_visit.n = 2 THEN
            INSERT INTO result_return (appointment_id, customer_id, clinic_id, scheduled_at, note, status,
                                       arrived_at, verified_by_id, created_at, version)
            VALUES (v_appt, v_me, v_clinic, ((v_day + 3) + TIME '09:00') AT TIME ZONE v_tz,
                    'Nhận kết quả Holter điện tim 24 giờ', 'ARRIVED',
                    ((v_day + 3) + TIME '08:52') AT TIME ZONE v_tz, v_doc_uid, v_at + INTERVAL '3 hours', 0);
        END IF;
    END LOOP;

    -- ===================================================================== other visits
    -- A visit cancelled by the patient, and the next one, booked for two days from now.
    v_day := d0 - 60;
    IF extract(isodow FROM v_day) = 7 THEN v_day := v_day - 1; END IF;
    INSERT INTO appointment (customer_id, employee_id, clinic_id, appointment_date, start_time, end_time, reason,
                             status, booking_code, cancelled_at, cancel_reason, created_at, version)
    VALUES (v_me, v_doctor, v_clinic, v_day, TIME '10:00', TIME '10:30', 'Khám định kỳ', 'CANCELLED',
            'D' || upper(substr(md5('081-demo-full-cancelled'), 1, 9)),
            ((v_day - 1) + TIME '20:00') AT TIME ZONE v_tz, 'Bận công tác đột xuất',
            ((v_day - 5) + TIME '19:00') AT TIME ZONE v_tz, 0);

    v_day := d0 + 2;
    IF extract(isodow FROM v_day) = 7 THEN v_day := v_day + 1; END IF;
    INSERT INTO appointment (customer_id, employee_id, clinic_id, appointment_date, start_time, end_time, reason,
                             status, booking_code, created_at, version)
    VALUES (v_me, v_doctor, v_clinic, v_day, TIME '09:00', TIME '09:30',
            'Tái khám tim mạch, đánh giá dữ liệu thiết bị theo dõi', 'BOOKED',
            'D' || upper(substr(md5('081-demo-full-upcoming'), 1, 9)), now() - INTERVAL '1 day', 0)
    RETURNING id INTO v_up;

    -- ===================================================================== home monitoring
    INSERT INTO monitoring_order (customer_id, encounter_id, clinic_id, ordered_by_id, ordered_by_name, reason, decision,
                                  consent_note, decided_at, status, created_at, version)
    SELECT v_me, v_enc_v2, v_clinic, v_doc_uid, u.full_name,
           'Theo dõi nhịp tim, SpO2 và nhiệt độ tại nhà sau cơn tức ngực', 'ACCEPTED',
           'Người bệnh đồng ý đeo thiết bị và chia sẻ dữ liệu với bác sĩ điều trị',
           now() - INTERVAL '45 days', 'ACTIVE', now() - INTERVAL '45 days', 0
    FROM (SELECT 1) one LEFT JOIN users u ON u.id = v_doc_uid;

    IF v_doctor IS NOT NULL THEN
        INSERT INTO monitoring_assignment (customer_id, employee_id, assigned_at, status, note, assigned_by_id, created_at, version)
        VALUES (v_me, v_doctor, now() - INTERVAL '45 days', 'ACTIVE', 'Bác sĩ điều trị chính', v_doc_uid,
                now() - INTERVAL '45 days', 0),
               (v_mom, v_doctor, now() - INTERVAL '20 days', 'ACTIVE', 'Theo dõi SpO2 sau đợt cấp COPD', v_doc_uid,
                now() - INTERVAL '20 days', 0);
    END IF;

    INSERT INTO monitoring_note (customer_id, content, created_at, created_by_id, version)
    VALUES (v_me, 'Tuần đầu đeo thiết bị: nhịp tim nghỉ 68–78, SpO2 ổn định 96–99%. Có 1 lần nhịp nhanh 132 khi leo cầu thang, tự hết.',
            now() - INTERVAL '38 days', v_doc_uid, 0),
           (v_me, 'Dữ liệu 4 tuần ổn định, huyết áp giảm dần về mục tiêu. Tiếp tục theo dõi đến lần tái khám.',
            now() - INTERVAL '8 days', v_doc_uid, 0),
           (v_mom, 'SpO2 ban đêm có lúc 91–92%, đã dặn người nhà cho bà ngủ đầu cao và dùng thuốc hít đúng giờ.',
            now() - INTERVAL '12 days', v_doc_uid, 0);

    -- Two wrist monitors, worn and paired with this account's phone.
    INSERT INTO medical_device (clinic_id, device_code, serial_number, device_type, manufacturer, model, status,
                                battery_level, firmware_version, connectivity, last_seen_at, last_sync_at,
                                registered_at, paired_at, paired_by_user_id, created_at, version)
    VALUES (v_clinic, 'VC-DEMO-BN01', 'SN-DEMO-BN-0001', 'WRIST_MONITOR', 'VitalCare', 'VC Band 2', 'ASSIGNED',
            78, '2.4.1', 'ONLINE', now() - INTERVAL '5 minutes', now() - INTERVAL '5 minutes',
            now() - INTERVAL '90 days', now() - INTERVAL '45 days', v_user, now() - INTERVAL '90 days', 0)
    RETURNING id INTO v_dev_me;
    INSERT INTO medical_device (clinic_id, device_code, serial_number, device_type, manufacturer, model, status,
                                battery_level, firmware_version, connectivity, last_seen_at, last_sync_at,
                                registered_at, paired_at, paired_by_user_id, created_at, version)
    VALUES (v_clinic, 'VC-DEMO-BN02', 'SN-DEMO-BN-0002', 'WRIST_MONITOR', 'VitalCare', 'VC Band 2', 'ASSIGNED',
            54, '2.4.1', 'ONLINE', now() - INTERVAL '20 minutes', now() - INTERVAL '20 minutes',
            now() - INTERVAL '90 days', now() - INTERVAL '20 days', v_user, now() - INTERVAL '90 days', 0)
    RETURNING id INTO v_dev_mom;

    INSERT INTO device_assignment (customer_id, device_id, employee_id, clinical_encounter_id, assigned_at,
                                   expected_return_at, status, note, assigned_by_id, created_at, version)
    VALUES (v_me, v_dev_me, v_doctor, v_enc_v2, now() - INTERVAL '45 days', now() + INTERVAL '15 days', 'ACTIVE',
            'Đeo liên tục, sạc khi tắm. Trả thiết bị khi tái khám.', v_doc_uid, now() - INTERVAL '45 days', 0)
    RETURNING id INTO v_asg_me;
    INSERT INTO device_assignment (customer_id, device_id, employee_id, assigned_at, expected_return_at, status, note,
                                   assigned_by_id, created_at, version)
    VALUES (v_mom, v_dev_mom, v_doctor, now() - INTERVAL '20 days', now() + INTERVAL '40 days', 'ACTIVE',
            'Theo dõi SpO2 ban đêm.', v_doc_uid, now() - INTERVAL '20 days', 0)
    RETURNING id INTO v_asg_mom;

    -- Readings: every two hours for thirty days, then every ten minutes over the last day. Blood
    -- pressure falls slowly toward target as the medicine takes hold.
    INSERT INTO vital_signs (customer_id, device_id, device_assignment_id, heart_rate, spo2, temperature,
                             body_temperature, systolic_bp, diastolic_bp, respiratory_rate, measured_at, received_at,
                             sync_status, source, validation_status, client_record_id, created_at, version)
    SELECT v_me, v_dev_me, v_asg_me,
           round(72 + 7 * sin(extract(hour FROM t AT TIME ZONE v_tz) / 24.0 * 2 * pi() - pi() / 2) + (h % 7) - 3),
           round((97.0 + (h % 25) / 10.0)::numeric, 1),
           round((34.4 + (h % 13) / 10.0)::numeric, 1),
           round((36.5 + (h % 6) / 10.0)::numeric, 1),
           round(128 + 12 * least(age_days, 30) / 30.0 + (h % 9) - 4),
           round(80 + 6 * least(age_days, 30) / 30.0 + (h % 5) - 2),
           15 + h % 4,
           t, t + INTERVAL '40 seconds', 'SYNCED', 'REST', 'VALID',
           '081-me-' || extract(epoch FROM t)::bigint, t + INTERVAL '40 seconds', 0
    FROM (
        SELECT t, extract(epoch FROM now() - t) / 86400 AS age_days,
               (hashtext('081-me-' || t::text)::bigint & 2147483647) AS h
        FROM (SELECT generate_series(date_trunc('hour', now()) - INTERVAL '30 days',
                                     date_trunc('hour', now()) - INTERVAL '1 day', INTERVAL '2 hours') AS t
              UNION ALL
              SELECT generate_series(date_trunc('hour', now()) - INTERVAL '1 day' + INTERVAL '10 minutes',
                                     now() - INTERVAL '5 minutes', INTERVAL '10 minutes')) s
    ) r;

    INSERT INTO vital_signs (customer_id, device_id, device_assignment_id, heart_rate, spo2, temperature,
                             body_temperature, respiratory_rate, measured_at, received_at, sync_status, source,
                             validation_status, client_record_id, created_at, version)
    SELECT v_mom, v_dev_mom, v_asg_mom,
           82 + h % 10, round((93.0 + (h % 40) / 10.0)::numeric, 1), round((34.2 + (h % 11) / 10.0)::numeric, 1),
           round((36.6 + (h % 5) / 10.0)::numeric, 1), 18 + h % 5,
           t, t + INTERVAL '1 minute', 'SYNCED', 'REST', 'VALID',
           '081-mom-' || extract(epoch FROM t)::bigint, t + INTERVAL '1 minute', 0
    FROM (SELECT t, (hashtext('081-mom-' || t::text)::bigint & 2147483647) AS h
          FROM generate_series(date_trunc('hour', now()) - INTERVAL '20 days', now() - INTERVAL '20 minutes',
                               INTERVAL '3 hours') AS t) r;

    -- Three abnormal readings and the alerts they raised: one handled, one being handled, one new.
    FOR v_visit IN
        SELECT * FROM (VALUES
          (1, v_me,  v_dev_me,  v_asg_me,  now() - INTERVAL '38 days', 'HEART_RATE', 'ABOVE', 120, 132.0, 'WARNING',
           'RESOLVED', 'Nhịp tim 132 lần/phút, cao hơn ngưỡng 120',
           'Đã gọi điện: người bệnh vừa leo cầu thang, nghỉ 10 phút nhịp về 84. Không cần xử trí thêm.'),
          (2, v_mom, v_dev_mom, v_asg_mom, now() - INTERVAL '6 days', 'SPO2', 'BELOW', 92, 89.0, 'CRITICAL',
           'ACKNOWLEDGED', 'SpO2 89%, thấp hơn ngưỡng 92%',
           'Đã liên hệ người nhà, hướng dẫn khí dung và ngồi dậy. Theo dõi tiếp.'),
          (3, v_me,  v_dev_me,  v_asg_me,  now() - INTERVAL '3 hours', 'HEART_RATE', 'ABOVE', 120, 126.0, 'WARNING',
           'OPEN', 'Nhịp tim 126 lần/phút, cao hơn ngưỡng 120', NULL)
        ) AS a(n, customer_id, device_id, assignment_id, at, metric, operator, threshold, measured, severity,
               status, message, notes)
    LOOP
        INSERT INTO vital_signs (customer_id, device_id, device_assignment_id, heart_rate, spo2, temperature,
                                 body_temperature, respiratory_rate, measured_at, received_at, sync_status, source,
                                 validation_status, client_record_id, created_at, version)
        VALUES (v_visit.customer_id, v_visit.device_id, v_visit.assignment_id,
                CASE WHEN v_visit.metric = 'HEART_RATE' THEN v_visit.measured::int ELSE 96 END,
                CASE WHEN v_visit.metric = 'SPO2' THEN v_visit.measured ELSE 97.0 END,
                35.1, 36.8, 20, v_visit.at, v_visit.at + INTERVAL '30 seconds', 'SYNCED', 'REST', 'VALID',
                '081-alert-' || v_visit.n, v_visit.at + INTERVAL '30 seconds', 0)
        RETURNING id INTO v_vital;

        INSERT INTO alert (vital_sign_id, alert_type, severity, message, status, triggered_at, acknowledged_at,
                           acknowledged_by_id, resolved_at, resolved_by_id, customer_id, clinic_id, device_id, rule_id,
                           metric, operator, threshold, measured_value, last_value, last_triggered_at, occurrences,
                           handling_notes, created_at, version)
        VALUES (v_vital, 'VITAL_SIGN', v_visit.severity, v_visit.message, v_visit.status, v_visit.at,
                CASE WHEN v_visit.status <> 'OPEN' THEN v_visit.at + INTERVAL '12 minutes' END,
                CASE WHEN v_visit.status <> 'OPEN' THEN v_doc_uid END,
                CASE WHEN v_visit.status = 'RESOLVED' THEN v_visit.at + INTERVAL '40 minutes' END,
                CASE WHEN v_visit.status = 'RESOLVED' THEN v_doc_uid END,
                v_visit.customer_id, v_clinic, v_visit.device_id,
                (SELECT r.id FROM alert_rule r WHERE r.clinic_id = v_clinic AND r.metric = v_visit.metric
                   AND r.operator = v_visit.operator AND r.customer_id IS NULL AND r.deleted_at IS NULL
                 ORDER BY r.id LIMIT 1),
                v_visit.metric, v_visit.operator, v_visit.threshold, v_visit.measured, v_visit.measured, v_visit.at, 1,
                v_visit.notes, v_visit.at, 0)
        RETURNING id INTO v_alert;

        INSERT INTO notification (user_id, alert_id, type, level, title, content, link, status, sent_at, read_at,
                                  created_at, version)
        VALUES (v_user, v_alert, 'alert', CASE WHEN v_visit.severity = 'CRITICAL' THEN 'ERROR' ELSE 'WARNING' END,
                'Chỉ số sức khỏe bất thường', v_visit.message || '. Phòng khám đã được báo.', '/my-health', 'SENT',
                v_visit.at, CASE WHEN v_visit.status = 'OPEN' THEN NULL ELSE v_visit.at + INTERVAL '1 hour' END,
                v_visit.at, 0);
    END LOOP;

    -- ===================================================================== sharing, chat, notifications
    -- Shares with the test caregiver (0911000009) and is the caregiver of the test patient (0911000007).
    INSERT INTO sharing_permission (customer_id, caregiver_user_id, share_vitals, share_alerts, share_appointments,
                                    emergency_phone, note, status, created_at, version)
    SELECT v_me, u.id, TRUE, TRUE, TRUE, u.phone, 'Người chăm sóc chính khi tôi đi công tác', 'ACTIVE',
           now() - INTERVAL '40 days', 0
    FROM users u WHERE u.phone = '+84911000009' AND u.deleted_at IS NULL;

    INSERT INTO sharing_permission (customer_id, caregiver_user_id, share_vitals, share_alerts, share_appointments,
                                    emergency_phone, note, status, created_at, version)
    SELECT c.id, v_user, TRUE, TRUE, TRUE, v_phone, 'Nhờ anh Bình theo dõi giúp chỉ số tim mạch', 'ACTIVE',
           now() - INTERVAL '30 days', 0
    FROM users u JOIN customer c ON c.user_id = u.id AND c.deleted_at IS NULL
    WHERE u.phone = '+84911000007' AND u.deleted_at IS NULL;

    INSERT INTO conversation (type, status, clinic_id, customer_user_id, contact_name, contact_phone, last_message_at,
                              last_message_preview, last_sender_kind, customer_read_at, staff_read_at, created_at, version)
    VALUES ('SUPPORT', 'OPEN', v_clinic, v_user, 'Nguyễn Văn Bình', v_phone, now() - INTERVAL '2 days',
            'Dạ anh cứ mang theo thiết bị khi tái khám nhé.', 'STAFF', now() - INTERVAL '1 day',
            now() - INTERVAL '2 days', now() - INTERVAL '6 days', 0)
    RETURNING id INTO v_conv;

    INSERT INTO message (conversation_id, sender_id, sender_kind, content, message_type, sent_at, created_at, version)
    SELECT v_conv, CASE WHEN m.kind = 'CUSTOMER' THEN v_user ELSE v_doc_uid END, m.kind, m.content, 'TEXT',
           now() - m.ago, now() - m.ago, 0
    FROM (VALUES
      ('CUSTOMER', 'Chào phòng khám, kết quả Holter huyết áp của tôi đã có chưa ạ?', INTERVAL '6 days'),
      ('STAFF', 'Chào anh Bình, kết quả đã có trong mục Kết quả của lượt khám ngày tái khám. Huyết áp 24 giờ của anh đạt mục tiêu ạ.', INTERVAL '6 days' - INTERVAL '25 minutes'),
      ('CUSTOMER', 'Cảm ơn bác sĩ. Thiết bị đeo tay có cần sạc mỗi ngày không ạ?', INTERVAL '3 days'),
      ('STAFF', 'Anh sạc khoảng 2 ngày một lần, nên sạc lúc tắm để không mất dữ liệu ban đêm.', INTERVAL '3 days' - INTERVAL '10 minutes'),
      ('CUSTOMER', 'Vâng, lần tái khám tới tôi có cần mang thiết bị theo không?', INTERVAL '2 days' + INTERVAL '30 minutes'),
      ('STAFF', 'Dạ anh cứ mang theo thiết bị khi tái khám nhé.', INTERVAL '2 days')
    ) AS m(kind, content, ago);

    INSERT INTO notification (user_id, type, level, title, content, link, status, sent_at, read_at, created_at, version)
    SELECT v_user, n.type, n.level, n.title, n.content, n.link, 'SENT', now() - n.ago,
           CASE WHEN n.is_read THEN now() - n.ago + INTERVAL '1 hour' END, now() - n.ago, 0
    FROM (VALUES
      ('appointment', 'SUCCESS', 'Đặt lịch khám thành công', 'Lịch tái khám tim mạch lúc 09:00, ngày ' || to_char(v_day, 'DD/MM/YYYY') || '.',
       '/my-appointments/show/' || v_up, INTERVAL '1 day', FALSE),
      ('result', 'SUCCESS', 'Lần khám đã hoàn tất', 'Xem kết quả khám và hướng dẫn của bác sĩ.', '/my-results', INTERVAL '7 days', TRUE),
      ('result', 'INFO', 'Đã có kết quả NT-proBNP', 'Bác sĩ sẽ xem kết quả và tư vấn cho bạn.', '/my-results', INTERVAL '7 days' - INTERVAL '2 hours', TRUE),
      ('chat', 'INFO', 'Phòng khám đã trả lời', 'Dạ anh cứ mang theo thiết bị khi tái khám nhé.', '/my-conversations', INTERVAL '2 days', FALSE),
      ('device', 'INFO', 'Thiết bị đã được giao', 'Thiết bị VC-DEMO-BN01 đã được gán cho bạn để theo dõi tại nhà.', '/my-devices', INTERVAL '45 days', TRUE)
    ) AS n(type, level, title, content, link, ago, is_read);
END
$demo$;

--rollback -- Left in place: the demo account may have new data under it by then.
