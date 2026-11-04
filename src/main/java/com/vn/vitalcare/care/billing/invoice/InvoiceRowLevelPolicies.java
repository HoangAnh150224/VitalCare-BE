package com.vn.vitalcare.care.billing.invoice;

import com.vn.vitalcare.entity.Invoice;
import com.vn.vitalcare.share.security.rowlevel.DefaultScope;
import com.vn.vitalcare.share.security.rowlevel.FieldDescriptor;
import com.vn.vitalcare.share.security.rowlevel.RowLevelPolicySet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** A bill belongs to the clinic that issued it (SRS BR-04). */
@Component
public class InvoiceRowLevelPolicies implements RowLevelPolicySet<Invoice> {

    @Override
    public String resource() {
        return "invoices";
    }

    @Override
    public Class<Invoice> entityClass() {
        return Invoice.class;
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
