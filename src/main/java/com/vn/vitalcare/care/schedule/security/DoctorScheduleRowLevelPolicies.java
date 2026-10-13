package com.vn.vitalcare.care.schedule.security;

import com.vn.vitalcare.entity.DoctorSchedule;
import com.vn.vitalcare.entity.ScheduleStatus;
import com.vn.vitalcare.share.security.rowlevel.DefaultScope;
import com.vn.vitalcare.share.security.rowlevel.FieldDescriptor;
import com.vn.vitalcare.share.security.rowlevel.RowLevelPolicySet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Doctor schedules under row-level management: a shift belongs to the clinic
 * it is worked at (SRS BR-04). {@code NONE} by default, for the reason given
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
                FieldDescriptor.column("clinicId", UUID.class),
                FieldDescriptor.column("status", ScheduleStatus.class));
    }

    @Override
    public Set<String> notCheckSafe() {
        return Set.of("id");
    }
}
