package com.vn.vitalcare.care.encounter.security;

import com.vn.vitalcare.entity.ClinicalEncounter;
import com.vn.vitalcare.entity.EncounterStatus;
import com.vn.vitalcare.share.security.rowlevel.DefaultScope;
import com.vn.vitalcare.share.security.rowlevel.FieldDescriptor;
import com.vn.vitalcare.share.security.rowlevel.RowLevelPolicySet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Clinical encounters under row-level management: an encounter belongs to the
 * clinic it happened at (SRS BR-04). {@code NONE} by default, for the reason
 * given on {@code EmployeeRowLevelPolicies}.
 */
@Component
public class ClinicalEncounterRowLevelPolicies implements RowLevelPolicySet<ClinicalEncounter> {

    @Override
    public String resource() {
        return "clinical_encounters";
    }

    @Override
    public Class<ClinicalEncounter> entityClass() {
        return ClinicalEncounter.class;
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
                FieldDescriptor.column("status", EncounterStatus.class));
    }

    @Override
    public Set<String> notCheckSafe() {
        return Set.of("id");
    }
}
