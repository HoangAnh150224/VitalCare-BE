--liquibase formatted sql

--changeset vitalcare:020-clinic-row-level-policies
--comment Starting row-level scopes for staff, appointments and devices: administrators see every clinic, clinic roles their own.

-- Staff, appointments and devices default to NONE (see *RowLevelPolicies):
-- a role with no scope reaches no rows. These are the scopes the application
-- starts with, and nothing more than data -- the Row-level policies screen
-- edits and extends them, and a role created later gets its own there.
--
-- The clinic roles compare a row's clinic with the caller's, from their staff
-- record. An account holding one with no staff record has no clinic, the
-- comparison is unknown, and the row is refused.
--
-- Joined by role code. A role this database does not have is skipped rather
-- than failing the migration: CLINIC_ADMIN is created at runtime, on the
-- Roles screen, and may not exist yet where this runs.

INSERT INTO row_level_policy (kind, role_id, resource, action, name, policy_group, description, scope, enabled, created_at)
SELECT 'SCOPE', r.id, v.resource, v.action, v.name, 'clinic', v.description, v.scope::jsonb, TRUE, now()
FROM (VALUES
  ('ADMIN', 'employees',    'read',  'Mọi cơ sở', 'Quản trị hệ thống thấy nhân viên của mọi cơ sở.', '{"all": []}'),
  ('ADMIN', 'employees',    'write', 'Mọi cơ sở', 'Quản trị hệ thống thêm và sửa nhân viên ở mọi cơ sở.', '{"all": []}'),
  ('ADMIN', 'appointments', 'read',  'Mọi cơ sở', 'Quản trị hệ thống thấy lịch hẹn của mọi cơ sở.', '{"all": []}'),
  ('ADMIN', 'appointments', 'write', 'Mọi cơ sở', 'Quản trị hệ thống đặt, check-in và huỷ lịch hẹn ở mọi cơ sở.', '{"all": []}'),
  ('ADMIN', 'devices',      'read',  'Mọi cơ sở', 'Quản trị hệ thống thấy thiết bị của mọi cơ sở.', '{"all": []}'),
  ('ADMIN', 'devices',      'write', 'Mọi cơ sở', 'Quản trị hệ thống đăng ký và sửa thiết bị ở mọi cơ sở.', '{"all": []}'),

  ('CLINIC_ADMIN', 'employees',    'read',  'Cơ sở của mình', 'Nhân viên cùng cơ sở.', '{"field": "clinicId", "op": "eq", "value": {"ctx": "user.clinicId"}}'),
  ('CLINIC_ADMIN', 'employees',    'write', 'Cơ sở của mình', 'Thêm và sửa nhân viên trong cơ sở của mình.', '{"field": "clinicId", "op": "eq", "value": {"ctx": "user.clinicId"}}'),
  ('CLINIC_ADMIN', 'appointments', 'read',  'Cơ sở của mình', 'Lịch hẹn tại cơ sở của mình.', '{"field": "clinicId", "op": "eq", "value": {"ctx": "user.clinicId"}}'),
  ('CLINIC_ADMIN', 'appointments', 'write', 'Cơ sở của mình', 'Đặt, check-in và huỷ lịch hẹn tại cơ sở của mình.', '{"field": "clinicId", "op": "eq", "value": {"ctx": "user.clinicId"}}'),
  ('CLINIC_ADMIN', 'devices',      'read',  'Cơ sở của mình', 'Thiết bị của cơ sở mình.', '{"field": "clinicId", "op": "eq", "value": {"ctx": "user.clinicId"}}'),
  ('CLINIC_ADMIN', 'devices',      'write', 'Cơ sở của mình', 'Đăng ký và sửa thiết bị của cơ sở mình.', '{"field": "clinicId", "op": "eq", "value": {"ctx": "user.clinicId"}}'),

  -- The front desk: sees its clinic's staff to assign them, but does not add or edit staff.
  ('MANAGER', 'employees',    'read',  'Cơ sở của mình', 'Nhân viên cùng cơ sở, để phân công chăm sóc.', '{"field": "clinicId", "op": "eq", "value": {"ctx": "user.clinicId"}}'),
  ('MANAGER', 'appointments', 'read',  'Cơ sở của mình', 'Lịch hẹn tại cơ sở của mình.', '{"field": "clinicId", "op": "eq", "value": {"ctx": "user.clinicId"}}'),
  ('MANAGER', 'appointments', 'write', 'Cơ sở của mình', 'Đặt, check-in và huỷ lịch hẹn tại cơ sở của mình.', '{"field": "clinicId", "op": "eq", "value": {"ctx": "user.clinicId"}}'),
  ('MANAGER', 'devices',      'read',  'Cơ sở của mình', 'Thiết bị của cơ sở mình.', '{"field": "clinicId", "op": "eq", "value": {"ctx": "user.clinicId"}}'),
  ('MANAGER', 'devices',      'write', 'Cơ sở của mình', 'Đăng ký và sửa thiết bị của cơ sở mình.', '{"field": "clinicId", "op": "eq", "value": {"ctx": "user.clinicId"}}')
) AS v(role_code, resource, action, name, description, scope)
JOIN roles r ON r.code = v.role_code;

--rollback DELETE FROM row_level_policy WHERE policy_group = 'clinic' AND resource IN ('employees', 'appointments', 'devices');
