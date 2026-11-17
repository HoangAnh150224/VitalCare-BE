--liquibase formatted sql

--changeset vitalcare:083-one-bill-for-a-visits-services splitStatements:false
--comment The services ordered in a visit are billed together: one service bill per visit while it is unpaid, a line per ordered service, so the desk takes the money once. Each line names the order it is for; a bill from when every service had its own keeps naming it on the bill too, and its line is filled in from there.

ALTER TABLE invoice_item ADD COLUMN service_order_id bigint;
ALTER TABLE invoice_item
    ADD CONSTRAINT fk_invoice_item_service_order FOREIGN KEY (service_order_id) REFERENCES service_order (id);
CREATE INDEX ix_invoice_item_service_order ON invoice_item (service_order_id);

UPDATE invoice_item it
SET service_order_id = i.service_order_id
FROM invoice i
WHERE it.invoice_id = i.id AND i.service_order_id IS NOT NULL AND it.service_order_id IS NULL;

--rollback DROP INDEX ix_invoice_item_service_order;
--rollback ALTER TABLE invoice_item DROP CONSTRAINT fk_invoice_item_service_order, DROP COLUMN service_order_id;
