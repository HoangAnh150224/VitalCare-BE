package com.vn.vitalcare.care.staff.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The roles a member of staff may hold at their clinic (SRS 3.18.10), apart
 * from the kind of staff they are: what a clinic administrator gives, and
 * what only the system administrator gives.
 */
public final class StaffRoles {

    /** Code → the name the clinic's screens use. */
    public static final Map<String, String> CLINIC_LEVEL = new LinkedHashMap<>();

    static {
        CLINIC_LEVEL.put("DOCTOR", "Bác sĩ");
        CLINIC_LEVEL.put("MANAGER", "Lễ tân");
        CLINIC_LEVEL.put("CLINIC_ADMIN", "Quản trị phòng khám");
    }

    /** Given only by the system administrator: the clinic's own administrator does not make another. */
    public static final Set<String> ADMIN_ONLY = Set.of("CLINIC_ADMIN");

    /**
     * The five groups the permission matrix shows, each as the resources it
     * covers: a role is "allowed" in a group when it may change something in
     * it, "by permission" when it may only read, and "not granted" otherwise.
     */
    public static final Map<String, List<String>> GROUPS = new LinkedHashMap<>();

    static {
        GROUPS.put("Khám chữa bệnh", List.of("clinical_encounters", "service_orders", "medical_records", "result_returns"));
        GROUPS.put("Đăng ký khám & hàng đợi", List.of("appointments", "exam_rooms", "doctor_schedules", "invoices"));
        GROUPS.put("Theo dõi & cảnh báo", List.of("vital_signs", "alerts", "alert_rules", "devices", "monitoring_orders",
                "monitoring_assignments", "sync_failures"));
        GROUPS.put("Bệnh nhân & tin nhắn", List.of("customers", "conversations", "feedback"));
        GROUPS.put("Nội dung & báo cáo", List.of("first_aid_articles", "reports", "licenses", "employees"));
    }

    private StaffRoles() {
    }
}
