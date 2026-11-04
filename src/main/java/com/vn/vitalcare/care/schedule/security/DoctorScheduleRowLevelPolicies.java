package com.vn.vitalcare.care.schedule.security;

import com.vn.vitalcare.entity.DoctorSchedule;
import com.vn.vitalcare.share.security.rowlevel.DefaultScope;
import com.vn.vitalcare.share.security.rowlevel.FieldDescriptor;
import com.vn.vitalcare.share.security.rowlevel.RowLevelPolicySet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * A doctor's blocked time under row-level management: it belongs to the
 * clinic the doctor works at (SRS BR-04). {@code NONE} by default, for the reason given
 * on {@code EmployeeRowLevelPolicies}.
 */
@Component
public class DoctorScheduleRowLevelPolicies implements RowLevelPolicySet<DoctorSchedule> {

    @Override
    public String resource() {
        return "doctor_schedules";
    }

    @Override
    public Class<DoctorSchedule> entityClass() {
        return DoctorSchedule.class;
    }

    @Override
    public DefaultScope defaultScope() {
        return DefaultScope.NONE;
    }

    @Override
    public List<FieldDescriptor> fields() {
        return List.of(
                FieldDescriptor.id("id"),
                FieldDescriptor.column("clinicId", UUID.class));
    }

    @Override
    public Set<String> notCheckSafe() {
        return Set.of("id");
    }
}
