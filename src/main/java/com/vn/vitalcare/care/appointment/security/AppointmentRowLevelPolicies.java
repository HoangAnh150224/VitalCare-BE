package com.vn.vitalcare.care.appointment.security;

import com.vn.vitalcare.entity.Appointment;
import com.vn.vitalcare.entity.AppointmentStatus;
import com.vn.vitalcare.share.security.rowlevel.DefaultScope;
import com.vn.vitalcare.share.security.rowlevel.FieldDescriptor;
import com.vn.vitalcare.share.security.rowlevel.RowLevelPolicySet;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Appointments under row-level management, for the staff screens.
 *
 * <p>A customer's own appointments do not go through this: they are pinned to
 * the customer by {@code my_appointments}, and a customer holds no scope here.
 * {@code NONE} by default, for the reason given on
 * {@code EmployeeRowLevelPolicies}.
 */
@Component
public class AppointmentRowLevelPolicies implements RowLevelPolicySet<Appointment> {

    @Override
    public String resource() {
        return "appointments";
    }

    @Override
    public Class<Appointment> entityClass() {
        return Appointment.class;
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
                FieldDescriptor.column("status", AppointmentStatus.class, false),
                FieldDescriptor.column("appointmentDate", LocalDate.class));
    }

    @Override
    public Set<String> notCheckSafe() {
        return Set.of("id");
    }
}
