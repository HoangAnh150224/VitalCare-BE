package com.vn.vitalcare.care.serviceorder;

import com.vn.vitalcare.entity.ServiceOrder;
import com.vn.vitalcare.share.security.rowlevel.DefaultScope;
import com.vn.vitalcare.share.security.rowlevel.FieldDescriptor;
import com.vn.vitalcare.share.security.rowlevel.RowLevelPolicySet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Service orders belong to the clinic of the encounter they were made in (SRS BR-04). */
@Component
public class ServiceOrderRowLevelPolicies implements RowLevelPolicySet<ServiceOrder> {

    @Override
    public String resource() {
        return "service_orders";
    }

    @Override
    public Class<ServiceOrder> entityClass() {
        return ServiceOrder.class;
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
