--liquibase formatted sql

--changeset vitalcare:055-single-system-admin splitStatements:false
--comment Keep a unique database constraint on the single SYSTEM_ADMIN role assignment.
DO $do$
DECLARE
    admin_role_id BIGINT;
    primary_admin_id BIGINT;
BEGIN
    SELECT id INTO admin_role_id FROM roles WHERE code = 'ADMIN';
    IF admin_role_id IS NULL THEN
        RAISE EXCEPTION 'The seeded ADMIN role is required before enforcing the singleton';
    END IF;

    SELECT ur.user_id INTO primary_admin_id
    FROM user_roles ur
    JOIN users u ON u.id = ur.user_id
    WHERE ur.role_id = admin_role_id
    ORDER BY CASE WHEN u.status = 'ACTIVE' AND u.deleted_at IS NULL THEN 0 ELSE 1 END, ur.user_id
    LIMIT 1;
    IF primary_admin_id IS NULL THEN
        RAISE EXCEPTION 'The deployment must seed one System Administrator account';
    END IF;

    DELETE FROM user_roles
    WHERE role_id = admin_role_id AND user_id <> primary_admin_id;

    EXECUTE format(
        'CREATE UNIQUE INDEX uq_single_system_admin_role ON user_roles ((1)) WHERE role_id = %s',
        admin_role_id);
END;
$do$;
