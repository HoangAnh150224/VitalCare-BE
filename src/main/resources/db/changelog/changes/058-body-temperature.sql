--liquibase formatted sql

--changeset vitalcare:058-body-temperature
--comment The band now sends an estimated body temperature (skin + calibrated offset) next to the wrist skin's (~34 °C). Both are stored; the default fever rules (37.5 / 38.5 °C) were body thresholds all along and never fired on skin, so they move to BODY_TEMPERATURE. Alerts already raised stay as they are.

ALTER TABLE vital_signs ADD COLUMN body_temperature NUMERIC(5,2);

ALTER TABLE alert_rule DROP CONSTRAINT ck_alert_rule_metric;
ALTER TABLE alert_rule ADD CONSTRAINT ck_alert_rule_metric
    CHECK (metric IN ('HEART_RATE', 'SPO2', 'SKIN_TEMPERATURE', 'BODY_TEMPERATURE'));

UPDATE alert_rule
SET metric = 'BODY_TEMPERATURE',
    name = CASE name WHEN 'Nhiệt độ da cao' THEN 'Nhiệt độ cơ thể cao' WHEN 'Nhiệt độ da rất cao' THEN 'Nhiệt độ cơ thể rất cao' END
WHERE metric = 'SKIN_TEMPERATURE' AND name IN ('Nhiệt độ da cao', 'Nhiệt độ da rất cao');

--rollback UPDATE alert_rule SET name = CASE name WHEN 'Nhiệt độ cơ thể cao' THEN 'Nhiệt độ da cao' WHEN 'Nhiệt độ cơ thể rất cao' THEN 'Nhiệt độ da rất cao' ELSE name END WHERE metric = 'BODY_TEMPERATURE';
--rollback UPDATE alert_rule SET metric = 'SKIN_TEMPERATURE' WHERE metric = 'BODY_TEMPERATURE';
--rollback ALTER TABLE alert_rule DROP CONSTRAINT ck_alert_rule_metric;
--rollback ALTER TABLE alert_rule ADD CONSTRAINT ck_alert_rule_metric CHECK (metric IN ('HEART_RATE', 'SPO2', 'SKIN_TEMPERATURE'));
--rollback ALTER TABLE vital_signs DROP COLUMN body_temperature;
