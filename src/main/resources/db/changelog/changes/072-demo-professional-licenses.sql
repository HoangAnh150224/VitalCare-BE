--liquibase formatted sql
--changeset vitalcare:072-demo-professional-licenses
-- Demo data only: match existing seeded employees by stable code or test phone.
-- Preserve existing license numbers; do not create staff, clinics or fake scan files.
CREATE TEMP TABLE demo_license_targets ON COMMIT DROP AS
SELECT e.id AS employee_id, e.clinic_id,
       COALESCE(NULLIF(trim(e.license_no), ''), 'DEMO-CCHN-' || e.employee_code) AS license_number,
       'Chứng chỉ hành nghề - ' || COALESCE(NULLIF(trim(e.specialty), ''), 'Khám chữa bệnh') || ' (dữ liệu mẫu)' AS title,
       CASE WHEN e.employee_code = 'NV-DEMO-01' THEN DATE '2028-11-14'
            WHEN e.employee_code = 'NV-DEMO-02' THEN NULL::date
            WHEN e.employee_code = 'NV-DEMO-04' THEN DATE '2026-12-14'
            WHEN u.phone = '+84911000003' THEN DATE '2026-10-14'
            ELSE DATE '2028-11-14' END AS expires_on
FROM employee e
JOIN users u ON u.id = e.user_id
WHERE e.deleted_at IS NULL AND u.deleted_at IS NULL AND e.status = 'ACTIVE'
  AND e.clinic_id IS NOT NULL
  AND ((e.employee_code IN ('NV-DEMO-01', 'NV-DEMO-04') AND e.staff_type = 'DOCTOR')
       OR (e.employee_code = 'NV-DEMO-02' AND e.license_no = 'CCHN-000002')
       OR (u.phone IN ('+84911000003', '+84911000004') AND e.staff_type = 'DOCTOR'))
  AND NOT EXISTS (SELECT 1 FROM professional_license l WHERE l.employee_id = e.id AND l.deleted_at IS NULL);

UPDATE employee e SET license_no = t.license_number, version = e.version + 1
FROM demo_license_targets t
WHERE e.id = t.employee_id AND NULLIF(trim(e.license_no), '') IS NULL;

INSERT INTO professional_license
    (employee_id, clinic_id, kind, title, license_number, issuer, issued_on, expires_on, created_at, version)
SELECT employee_id, clinic_id, 'LICENSE', title, license_number,
       'Đơn vị cấp mẫu - chỉ dùng kiểm thử', DATE '2024-01-15', expires_on, now(), 0
FROM demo_license_targets;
