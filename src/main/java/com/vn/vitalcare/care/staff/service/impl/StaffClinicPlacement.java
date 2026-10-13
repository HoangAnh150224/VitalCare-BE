package com.vn.vitalcare.care.staff.service.impl;

import com.vn.vitalcare.care.staff.repository.EmployeeRepository;
import com.vn.vitalcare.entity.Employee;
import com.vn.vitalcare.share.security.rowlevel.ClinicPlacement;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The clinic on an account's staff record, for {@code user.clinicId}.
 *
 * <p>Somebody with no staff record has no clinic, and a policy comparing a row
 * against a missing value refuses it. So a clinic-scoped role given to an
 * account that does not work anywhere sees nothing — rather than, as when no
 * clinic meant "every clinic", all of them.
 */
@Component
public class StaffClinicPlacement implements ClinicPlacement {

    private final EmployeeRepository employees;

    public StaffClinicPlacement(EmployeeRepository employees) {
        this.employees = employees;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> clinicOf(long userId) {
        return employees.findByUserIdAndDeletedAtIsNull(userId).map(Employee::getClinicId);
    }
}
