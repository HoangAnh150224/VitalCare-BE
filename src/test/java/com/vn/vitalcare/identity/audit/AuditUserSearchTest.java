package com.vn.vitalcare.identity.audit;

import com.vn.vitalcare.entity.*;
import com.vn.vitalcare.identity.user.entity.User;
import com.vn.vitalcare.identity.user.entity.UserStatus;
import com.vn.vitalcare.share.web.ListParams;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class AuditUserSearchTest {
    @Autowired EntityManager em;
    @Autowired AuditLogRepository logs;

    @Test
    void findsLegacyMembershipEndByTargetUserId() {
        User user = new User(java.util.UUID.randomUUID().toString().substring(0, 18), null,
                "hash", "Former administrator", UserStatus.INACTIVE);
        em.persist(user);
        Employee employee = new Employee();
        employee.setUser(user);
        employee.setEmployeeCode("AUD-" + user.getId());
        employee.setStaffType(StaffType.CLINIC_ADMIN);
        employee.setStatus(EmployeeStatus.INACTIVE);
        em.persist(employee);
        AuditLog entry = new AuditLog(null, "Administrator", "employees", employee.getId(),
                "membership_end", null, "{\"reason\":\"Nghỉ việc\"}",
                java.time.OffsetDateTime.now(), null, null, null);
        logs.saveAndFlush(entry);
        var query = new LinkedMultiValueMap<String, String>();
        query.add("q", user.getId().toString());
        assertThat(logs.findAll(AuditLogController.specification(new ListParams(query))))
                .extracting(AuditLog::getId).contains(entry.getId());
    }
}
