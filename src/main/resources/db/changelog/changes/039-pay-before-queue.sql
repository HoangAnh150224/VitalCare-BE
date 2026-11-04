--liquibase formatted sql

--changeset vitalcare:039-pay-before-queue
--comment BF-02: the examination fee is paid before the visit queues, and the queue is ordered by when it was paid. BF-03: each ordered service is billed on its own and paid before it is done.

ALTER TABLE appointment ADD COLUMN paid_at TIMESTAMPTZ;

-- Visits that got past the desk under the old rule (pay after the visit) count as paid
-- when they were checked in, so today's queue keeps moving and history reads the same.
UPDATE appointment SET paid_at = coalesce(checked_in_at, updated_at, created_at)
WHERE status IN ('CHECKED_IN', 'IN_EXAM', 'AWAITING_RESULTS', 'COMPLETED') AND paid_at IS NULL;

ALTER TABLE invoice
    ADD COLUMN kind VARCHAR(16) NOT NULL DEFAULT 'EXAM',
    ADD COLUMN service_order_id BIGINT,
    ADD CONSTRAINT ck_invoice_kind CHECK (kind IN ('EXAM', 'SERVICE')),
    ADD CONSTRAINT fk_invoice_service_order FOREIGN KEY (service_order_id) REFERENCES service_order (id);

-- One encounter now has several bills (its services'); what stays one-of is a visit's
-- examination bill and a service's own bill, while they are not cancelled.
DROP INDEX uq_invoice_encounter;
-- A visit seen in several encounters had a bill each under the old rule: the first stays its
-- examination bill, the others are kept as service bills so neither money nor history is lost.
UPDATE invoice SET kind = 'SERVICE'
WHERE id IN (SELECT id FROM (
    SELECT id, row_number() OVER (PARTITION BY appointment_id ORDER BY id) AS rn
    FROM invoice WHERE appointment_id IS NOT NULL AND status <> 'CANCELLED' AND deleted_at IS NULL) ranked
  WHERE rn > 1);
CREATE UNIQUE INDEX uq_invoice_exam_appointment ON invoice (appointment_id)
    WHERE kind = 'EXAM' AND status <> 'CANCELLED' AND deleted_at IS NULL;
CREATE UNIQUE INDEX uq_invoice_service_order ON invoice (service_order_id)
    WHERE status <> 'CANCELLED' AND deleted_at IS NULL;

--rollback DROP INDEX uq_invoice_service_order;
--rollback DROP INDEX uq_invoice_exam_appointment;
--rollback CREATE UNIQUE INDEX uq_invoice_encounter ON invoice (clinical_encounter_id) WHERE deleted_at IS NULL;
--rollback ALTER TABLE invoice DROP CONSTRAINT fk_invoice_service_order, DROP CONSTRAINT ck_invoice_kind, DROP COLUMN service_order_id, DROP COLUMN kind;
--rollback ALTER TABLE appointment DROP COLUMN paid_at;
