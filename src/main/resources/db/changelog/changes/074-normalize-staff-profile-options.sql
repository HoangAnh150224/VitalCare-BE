--liquibase formatted sql
--changeset vitalcare:074-normalize-staff-profile-options
-- Specialty, title and position are now picked from fixed lists (StaffProfileOptions).
-- Bring the values seeded as free text onto those lists. Anything else a clinic typed
-- in is left as it is: the API keeps accepting a value a record already holds.
UPDATE employee SET specialty = 'Tim mạch' WHERE trim(specialty) = 'Nội tim mạch';
UPDATE employee SET specialty = 'Hô hấp' WHERE trim(specialty) = 'Nội tổng quát';
-- A nurse's "specialty" only repeated the title.
UPDATE employee SET specialty = NULL WHERE trim(specialty) = 'Điều dưỡng';
UPDATE employee SET clinic_position = 'Bác sĩ điều trị' WHERE trim(clinic_position) = 'Bác sĩ';
