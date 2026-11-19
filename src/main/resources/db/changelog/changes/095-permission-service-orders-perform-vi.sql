--liquibase formatted sql

--changeset vitalcare:095-permission-service-orders-perform-vi
--comment KAN-63: service_orders:perform came with 084, after 090 renamed the rest, and was still in English.

UPDATE permissions
SET name = 'Thực hiện dịch vụ được chỉ định',
    description = 'Bắt đầu một dịch vụ đã được chỉ định, ghi kết quả và đính kèm tệp'
WHERE code = 'service_orders:perform';
