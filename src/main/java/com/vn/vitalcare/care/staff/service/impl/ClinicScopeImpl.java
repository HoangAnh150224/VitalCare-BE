package com.vn.vitalcare.care.staff.service.impl;

import com.vn.vitalcare.care.staff.repository.EmployeeRepository;
import com.vn.vitalcare.care.staff.service.ClinicScope;
import com.vn.vitalcare.entity.Clinic;
import com.vn.vitalcare.entity.Employee;
import com.vn.vitalcare.share.security.CurrentUser;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The {@link ClinicScope} the application runs on: the clinic on the caller's staff record. */
@Service
@Transactional(readOnly = true)
public class ClinicScopeImpl implements ClinicScope {

    private final EmployeeRepository employees;

    public ClinicScopeImpl(EmployeeRepository employees) {
        this.employees = employees;
    }

    @Override
    public Optional<UUID> currentClinicId() {
        // Read on every call rather than carried in the token, so moving
        // somebody to another clinic takes effect on their next request.
        return CurrentUser.id()
                .flatMap(employees::findByUserIdAndDeletedAtIsNull)
                .map(Employee::getClinic)
                .map(Clinic::getClinicId);
    }
}
