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

    /** KAN-50: the search box finds rows by the labels the list shows, not only by stored codes. */
    @Test
    void findsRowsByTheirVietnameseLabels() {
        AuditLog entry = logs.saveAndFlush(new AuditLog(null, "Administrator", "users", 1L, "delete",
                null, null, java.time.OffsetDateTime.now(), null, null, null));
        for (String term : new String[] {"Người dùng", "người dùng", "nguoi dung", "Xóa", "xoá"}) {
            var query = new LinkedMultiValueMap<String, String>();
            query.add("q", term);
            assertThat(logs.findAll(AuditLogController.specification(new ListParams(query))))
                    .as(term).extracting(AuditLog::getId).contains(entry.getId());
        }
        AuditLog clinic = logs.saveAndFlush(new AuditLog(null, "Administrator", "clinics", null, "create",
                null, "{\"name\":\"PK\"}", java.time.OffsetDateTime.now(), null, null, null));
        for (String term : new String[] {"Phòng khám", "Cơ sở", "Tạo mới"}) {
            var query = new LinkedMultiValueMap<String, String>();
            query.add("q", term);
            assertThat(logs.findAll(AuditLogController.specification(new ListParams(query))))
                    .as(term).extracting(AuditLog::getId).contains(clinic.getId());
        }
        var miss = new LinkedMultiValueMap<String, String>();
        miss.add("q", "Hóa đơn không có");
        assertThat(logs.findAll(AuditLogController.specification(new ListParams(miss))))
                .extracting(AuditLog::getId).doesNotContain(entry.getId());
    }
}
