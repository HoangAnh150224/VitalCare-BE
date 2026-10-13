package com.vn.vitalcare.care.staff.security;

import com.vn.vitalcare.entity.Employee;
import com.vn.vitalcare.entity.EmployeeStatus;
import com.vn.vitalcare.entity.StaffType;
import com.vn.vitalcare.share.security.rowlevel.DefaultScope;
import com.vn.vitalcare.share.security.rowlevel.FieldDescriptor;
import com.vn.vitalcare.share.security.rowlevel.RowLevelPolicySet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Staff under row-level management: which clinic's staff a role reaches is a
 * policy, written on the Row-level policies screen against
 * {@code user.clinicId}, not a rule in this slice.
 *
 * <p>{@code NONE} by default. A role nobody wrote a scope for sees no staff,
 * and the coverage grid shows it as a gap; the opposite default would hand a
 * newly created role every clinic's staff until somebody noticed.
 */
@Component
public class EmployeeRowLevelPolicies implements RowLevelPolicySet<Employee> {

    @Override
    public String resource() {
        return "employees";
    }

    @Override
    public Class<Employee> entityClass() {
        return Employee.class;
    }

    @Override
    public DefaultScope defaultScope() {
        return DefaultScope.NONE;
    }

    @Override
    public List<FieldDescriptor> fields() {
        return List.of(
                FieldDescriptor.id("id"),
                FieldDescriptor.column("clinicId", UUID.class),
                FieldDescriptor.column("staffType", StaffType.class, false),
                FieldDescriptor.column("status", EmployeeStatus.class, false));
    }

    @Override
    public Set<String> notCheckSafe() {
        return Set.of("id");
    }
}
