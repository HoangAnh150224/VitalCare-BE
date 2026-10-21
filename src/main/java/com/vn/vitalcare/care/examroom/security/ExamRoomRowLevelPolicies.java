package com.vn.vitalcare.care.examroom.security;

import com.vn.vitalcare.entity.ExamRoom;
import com.vn.vitalcare.share.security.rowlevel.DefaultScope;
import com.vn.vitalcare.share.security.rowlevel.FieldDescriptor;
import com.vn.vitalcare.share.security.rowlevel.RowLevelPolicySet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Examination rooms under row-level management: a room belongs to its clinic
 * (SRS BR-04). {@code NONE} by default, for the reason given on
 * {@code EmployeeRowLevelPolicies}.
 */
@Component
public class ExamRoomRowLevelPolicies implements RowLevelPolicySet<ExamRoom> {

    @Override
    public String resource() {
        return "exam_rooms";
    }

    @Override
    public Class<ExamRoom> entityClass() {
        return ExamRoom.class;
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
