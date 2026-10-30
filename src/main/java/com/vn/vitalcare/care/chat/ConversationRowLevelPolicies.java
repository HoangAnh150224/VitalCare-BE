package com.vn.vitalcare.care.chat;

import com.vn.vitalcare.entity.Conversation;
import com.vn.vitalcare.share.security.rowlevel.DefaultScope;
import com.vn.vitalcare.share.security.rowlevel.FieldDescriptor;
import com.vn.vitalcare.share.security.rowlevel.RowLevelPolicySet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** A conversation belongs to the clinic it was started with; staff answer their own clinic's (SRS BR-04). */
@Component
public class ConversationRowLevelPolicies implements RowLevelPolicySet<Conversation> {

    @Override
    public String resource() {
        return "conversations";
    }

    @Override
    public Class<Conversation> entityClass() {
        return Conversation.class;
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
