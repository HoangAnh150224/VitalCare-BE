--liquibase formatted sql

--changeset vitalcare:094-appointment-reason-500
--comment A visit's reason may run to 500 characters, as the booking forms and requests allow (KAN-88).

ALTER TABLE appointment ALTER COLUMN reason TYPE varchar(500);

--rollback ALTER TABLE appointment ALTER COLUMN reason TYPE varchar(255);

--changeset vitalcare:094-demo-service-bills-paid-before-results
--comment A service is done only once its bill is paid (BF-03), and the app refuses a result before that; but 081 seeded the newest demo visit's first service with its result in and its bill still owed (HD-DEMO-BN-016, KAN-90). Such demo bills are paid now, in cash at the desk as 081 pays the others, three minutes after they were issued. Only demo bills, and only where the result is already in.

WITH owed AS (
    SELECT i.id, i.total_amount, i.issued_at + INTERVAL '3 minutes' AS paid_at
    FROM invoice i
    WHERE i.invoice_number LIKE 'HD-DEMO-%'
      AND i.kind = 'SERVICE'
      AND i.status = 'UNPAID'
      AND EXISTS (
          SELECT 1
          FROM invoice_item it
          JOIN service_order o ON o.id = COALESCE(it.service_order_id, i.service_order_id)
          WHERE it.invoice_id = i.id AND o.status IN ('RESULT_READY', 'REVIEWED'))
), paid AS (
    UPDATE invoice i
    SET status = 'PAID', paid_at = owed.paid_at, due_at = NULL
    FROM owed
    WHERE i.id = owed.id
    RETURNING i.id, owed.total_amount, owed.paid_at
)
INSERT INTO payment (invoice_id, amount, payment_method, status, transaction_code, paid_at, created_at, version)
SELECT id, total_amount, 'CASH', 'PAID', 'DEMO' || id, paid_at, paid_at, 0
FROM paid;

--rollback -- Nothing to put back: these bills were owed only by mistake.
