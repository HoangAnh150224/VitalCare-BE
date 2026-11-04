package com.vn.vitalcare.care.device.security;

import com.vn.vitalcare.entity.DeviceStatus;
import com.vn.vitalcare.entity.MedicalDevice;
import com.vn.vitalcare.share.security.rowlevel.DefaultScope;
import com.vn.vitalcare.share.security.rowlevel.FieldDescriptor;
import com.vn.vitalcare.share.security.rowlevel.RowLevelPolicySet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Monitoring devices under row-level management. {@code NONE} by default, for
 * the reason given on {@code EmployeeRowLevelPolicies}.
 */
@Component
public class MedicalDeviceRowLevelPolicies implements RowLevelPolicySet<MedicalDevice> {

    @Override
    public String resource() {
        return "devices";
    }

    @Override
    public Class<MedicalDevice> entityClass() {
        return MedicalDevice.class;
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
                FieldDescriptor.column("status", DeviceStatus.class, false),
                FieldDescriptor.column("deviceType", String.class));
    }

    @Override
    public Set<String> notCheckSafe() {
        return Set.of("id");
    }
}
