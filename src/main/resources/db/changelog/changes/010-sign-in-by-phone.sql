--liquibase formatted sql

--changeset vitalcare:010-sign-in-by-phone
--comment Accounts sign in by phone number instead of username; email becomes optional.

-- The sign-in identifier moves from a chosen username to a phone number,
-- stored in E.164 (+84 and nine digits) so that uq_users_phone means one
-- account per number: 0901234567 and +84901234567 are one line, and only one
-- spelling may reach the column. UserService normalises before every write
-- and every lookup.
--
-- A migration of its own rather than an edit to 001/002: those had already
-- run on databases outside this one, and Liquibase refuses to start once an
-- applied changeset's checksum moves.

-- The seed accounts get deliberately unassignable placeholders: this file is
-- in a public repository, and a real number here would be a real number
-- published. Sign in with one, then change it from the user screen.
UPDATE users SET username = '+84900000001' WHERE username = 'admin';
UPDATE users SET username = '+84900000002' WHERE username = 'manager';
UPDATE users SET username = '+84900000003' WHERE username = 'viewer';

-- Any other account has a username no number can be derived from. Each gets
-- +840 followed by its id: no Vietnamese number starts with 0 after the
-- country code, so this cannot be anybody's real line, it is unique per row,
-- and it still signs in (as 0000000042 or +84000000042). An administrator
-- replaces it with the real number. Ids beyond eight digits would collide;
-- no database this runs on is near that.
UPDATE users SET username = '+840' || lpad(id::text, 8, '0')
WHERE username !~ '^\+84[0-9]{9}$';

ALTER TABLE users RENAME COLUMN username TO phone;
ALTER TABLE users ALTER COLUMN phone TYPE varchar(20);

-- Also the index every sign-in reads, so no separate one is needed.
ALTER TABLE users RENAME CONSTRAINT uq_users_username TO uq_users_phone;

-- Secondary, and only for sending notifications — nothing authenticates
-- against it. Optional because an account is perfectly usable without one;
-- uq_users_email stays, so two accounts cannot claim one inbox and cross
-- their notifications. Postgres permits many NULLs under it.
ALTER TABLE users ALTER COLUMN email DROP NOT NULL;

-- Rollback restores the shape, not the old usernames of non-seed accounts,
-- which were overwritten above. It also fails while any account has no
-- email, since the column cannot go back to NOT NULL over one.
--rollback ALTER TABLE users ALTER COLUMN email SET NOT NULL;
--rollback ALTER TABLE users RENAME CONSTRAINT uq_users_phone TO uq_users_username;
--rollback ALTER TABLE users ALTER COLUMN phone TYPE varchar(100);
--rollback ALTER TABLE users RENAME COLUMN phone TO username;
--rollback UPDATE users SET username = 'admin' WHERE username = '+84900000001';
--rollback UPDATE users SET username = 'manager' WHERE username = '+84900000002';
--rollback UPDATE users SET username = 'viewer' WHERE username = '+84900000003';
