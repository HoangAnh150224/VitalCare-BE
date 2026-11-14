--liquibase formatted sql
--changeset vitalcare:073-demo-revenue-invoices
-- Sample accounting data for the existing demo patient and the two seeded clinics.
-- No real payment gateway, messages or existing appointments are changed.
CREATE TEMP TABLE demo_revenue_bills ON COMMIT DROP AS
SELECT c.clinic_id, p.id AS customer_id, s.id AS service_id, s.name AS service_name,
       s.price, v.kind,
       'HD-DEMO-DT-' || left(c.clinic_id::text, 8) || '-' || d.n || '-' || v.kind AS number,
       (((now() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date - d.n) + TIME '08:00')
           AT TIME ZONE 'Asia/Ho_Chi_Minh' AS issued_at,
       CASE WHEN d.n = 0 THEN 'UNPAID' ELSE 'PAID' END AS status
FROM clinic c
CROSS JOIN generate_series(0, 6) AS d(n)
CROSS JOIN (VALUES ('EXAM', 'KHAM'), ('SERVICE', 'XN-CTM')) AS v(kind, code)
JOIN clinic_service s ON s.service_code = v.code AND s.deleted_at IS NULL
JOIN customer p ON p.phone = '+84900000501' AND p.user_id IS NOT NULL AND p.deleted_at IS NULL
WHERE c.clinic_id IN ('5b1f8c2e-6a3d-4f7e-9c41-2d8e0a7b3c19', 'c2a7e4d1-3b8f-4c62-9a15-7e0d3f9b2a48')
  AND NOT EXISTS (SELECT 1 FROM invoice i WHERE i.invoice_number =
       'HD-DEMO-DT-' || left(c.clinic_id::text, 8) || '-' || d.n || '-' || v.kind);

INSERT INTO invoice (customer_id, clinic_id, invoice_number, kind, subtotal, discount_amount,
                     total_amount, status, issued_at, paid_at, created_at, version)
SELECT customer_id, clinic_id, number, kind, price, 0, price, status, issued_at,
       CASE WHEN status = 'PAID' THEN issued_at + INTERVAL '15 minutes' END, issued_at, 0
FROM demo_revenue_bills;

INSERT INTO invoice_item (invoice_id, clinic_service_id, description, quantity, unit_price, amount, created_at, version)
SELECT i.id, d.service_id, d.service_name || ' (dữ liệu mẫu báo cáo doanh thu)', 1, d.price, d.price, d.issued_at, 0
FROM demo_revenue_bills d JOIN invoice i ON i.invoice_number = d.number;

INSERT INTO payment (invoice_id, amount, payment_method, status, paid_at, created_at, version)
SELECT i.id, i.total_amount, 'CASH', 'PAID', i.paid_at, i.paid_at, 0
FROM demo_revenue_bills d JOIN invoice i ON i.invoice_number = d.number
WHERE i.status = 'PAID';
