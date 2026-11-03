package com.vn.vitalcare.share.security;

/**
 * The permission codes seeded by {@code 005-seed-security.sql}, as constants.
 *
 * <p>These are compile-time constants so that {@code @PreAuthorize} can be
 * written as {@code hasAuthority(Permissions.USERS_WRITE)} — a typo becomes a
 * compile error instead of an endpoint that silently authorises nobody.
 *
 * <p>The code is {@code resource:action}, where the resource half is verbatim
 * the resource name the frontend declares in {@code routes.tsx}. That is not
 * cosmetic: it is what lets the admin UI's access control provider build the
 * code it needs from the resource and action it is already handed, rather than
 * carrying a second copy of this mapping that could drift.
 *
 * <p>Three actions cover the six Refine actions: {@code list}/{@code show} need
 * {@code read}, {@code create}/{@code edit}/{@code clone} need {@code write},
 * and {@code delete} needs {@code delete}.
 */
public final class Permissions {

    public static final String USERS_READ = "users:read";
    public static final String USERS_WRITE = "users:write";
    public static final String USERS_DELETE = "users:delete";

    public static final String ROLES_READ = "roles:read";
    public static final String ROLES_WRITE = "roles:write";
    public static final String ROLES_DELETE = "roles:delete";

    public static final String PERMISSIONS_READ = "permissions:read";

    // ADMIN only, and 005-seed-row-level-permissions spells that out rather
    // than joining: editing a data scope is editing the security model, so it
    // is not a grant to hand around. There is deliberately no row-level scope
    // on the policy table itself -- deciding which policies you may see would
    // need the policies you may see.
    public static final String ROW_LEVEL_POLICIES_READ = "row_level_policies:read";
    public static final String ROW_LEVEL_POLICIES_WRITE = "row_level_policies:write";
    public static final String ROW_LEVEL_POLICIES_DELETE = "row_level_policies:delete";

    // Seeded by 013-seed-care. activate and check_in are actions of their own:
    // turning somebody into a patient is not the same grant as editing their
    // address.
    public static final String CUSTOMERS_READ = "customers:read";
    public static final String CUSTOMERS_WRITE = "customers:write";
    public static final String CUSTOMERS_ACTIVATE = "customers:activate";
    // Seeded by 030: whether the clinic is still looking after somebody (SRS 3.8.5).
    public static final String CUSTOMERS_STATUS = "customers:status";

    public static final String APPOINTMENTS_READ = "appointments:read";
    public static final String APPOINTMENTS_WRITE = "appointments:write";
    public static final String APPOINTMENTS_CHECK_IN = "appointments:check_in";

    // Seeded by 015. Reading a clinic's hours needs no permission (booking
    // reads them); clinics:read is what puts the clinic screen in a menu.
    public static final String CLINICS_READ = "clinics:read";
    public static final String CLINICS_WRITE = "clinics:write";

    // Seeded by 017. customers:assign is putting a patient in somebody's care
    // team and on a device; my_patients is a member of clinical staff's own
    // list, pinned to the caller like my_appointments.
    public static final String EMPLOYEES_READ = "employees:read";
    public static final String EMPLOYEES_WRITE = "employees:write";
    public static final String DEVICES_READ = "devices:read";
    public static final String DEVICES_WRITE = "devices:write";
    /** Issue a device's MQTT/REST secret and run the device simulator. */
    public static final String DEVICES_OPERATE = "devices:operate";
    public static final String DEVICE_SYNCS_READ = "device_syncs:read";
    public static final String SYNC_FAILURES_READ = "sync_failures:read";
    public static final String SYNC_FAILURES_WRITE = "sync_failures:write";
    public static final String VITAL_SIGNS_READ = "vital_signs:read";
    public static final String MY_DEVICES_READ = "my_devices:read";
    public static final String MY_VITAL_SIGNS_READ = "my_vital_signs:read";
    public static final String ALERT_RULES_READ = "alert_rules:read";
    public static final String ALERT_RULES_WRITE = "alert_rules:write";
    public static final String ALERTS_READ = "alerts:read";
    public static final String ALERTS_WRITE = "alerts:write";
    public static final String MY_ALERTS_READ = "my_alerts:read";
    public static final String MONITORING_ORDERS_READ = "monitoring_orders:read";
    public static final String MONITORING_ORDERS_WRITE = "monitoring_orders:write";
    public static final String MY_SHARING_READ = "my_sharing:read";
    public static final String MY_SHARING_WRITE = "my_sharing:write";
    public static final String SHARED_PATIENTS_READ = "shared_patients:read";
    public static final String EMERGENCY_ALERTS_READ = "emergency_alerts:read";
    public static final String EMERGENCY_ALERTS_WRITE = "emergency_alerts:write";
    public static final String ESCALATIONS_READ = "escalations:read";
    public static final String RESULT_RETURNS_READ = "result_returns:read";
    public static final String RESULT_RETURNS_WRITE = "result_returns:write";
    public static final String MY_RESULT_RETURNS_READ = "my_result_returns:read";
    public static final String FEEDBACK_READ = "feedback:read";
    public static final String MY_FEEDBACK_READ = "my_feedback:read";
    public static final String MY_FEEDBACK_WRITE = "my_feedback:write";
    public static final String MY_LICENSES_READ = "my_licenses:read";
    public static final String MY_LICENSES_WRITE = "my_licenses:write";
    public static final String LICENSES_READ = "licenses:read";
    public static final String MY_DEVICES_WRITE = "my_devices:write";
    public static final String CUSTOMERS_ASSIGN = "customers:assign";
    // SRS 3.1.3: "Assign Patient to Healthcare Staff" is the clinic administrator's alone;
    // handing out a device stays with customers:assign.
    public static final String CUSTOMERS_ASSIGN_STAFF = "customers:assign_staff";
    public static final String MY_PATIENTS_READ = "my_patients:read";
    // Seeded by 028: a customer's own patient profile and their relatives'.
    public static final String MY_PROFILES_READ = "my_profiles:read";
    public static final String MY_PROFILES_WRITE = "my_profiles:write";

    // Seeded by 024. The visit and the record it adds to are the Doctor's and
    // Healthcare Staff's (SRS 3.1.3), not the clinic's or system's administrators'.
    public static final String CLINICAL_ENCOUNTERS_READ = "clinical_encounters:read";
    public static final String CLINICAL_ENCOUNTERS_WRITE = "clinical_encounters:write";
    /** Recording the diagnosis and completing the encounter: the doctor's sign-off. */
    public static final String CLINICAL_ENCOUNTERS_CONCLUDE = "clinical_encounters:conclude";
    public static final String MEDICAL_RECORDS_READ = "medical_records:read";
    public static final String MEDICAL_RECORDS_WRITE = "medical_records:write";

    // Seeded by 026. Everybody at the clinic reads the schedule; the doctor
    // and the clinic administrator change it (SRS 3.1.3).
    public static final String DOCTOR_SCHEDULES_READ = "doctor_schedules:read";
    public static final String DOCTOR_SCHEDULES_WRITE = "doctor_schedules:write";
    public static final String DOCTOR_SCHEDULES_DELETE = "doctor_schedules:delete";
    // The clinic's care-team assignments as a list; changing one stays customers:assign_staff.
    public static final String MONITORING_ASSIGNMENTS_READ = "monitoring_assignments:read";

    // Seeded by 018, ADMIN only: a customer's or a member of staff's own
    // screens, seen read-only. Every endpoint behind it is a GET.
    public static final String VIEW_AS_READ = "view_as:read";

    // The customer's own side of the appointment book. Every query behind it
    // is pinned to the caller's own customer record, whatever the request says.
    public static final String MY_APPOINTMENTS_READ = "my_appointments:read";
    public static final String MY_APPOINTMENTS_WRITE = "my_appointments:write";

    // Seeded by 031. Clinic staff see the rooms; the clinic administrator
    // sets them up; whoever works the floor calls patients in.
    public static final String EXAM_ROOMS_READ = "exam_rooms:read";
    public static final String EXAM_ROOMS_WRITE = "exam_rooms:write";
    public static final String EXAM_ROOMS_OPERATE = "exam_rooms:operate";

    // Seeded by 032. The catalogue is the system administrator's; every member
    // of staff reads it. Orders are worked by clinical staff; bills by the desk,
    // and the revenue report is the clinic administrator's.
    public static final String MEDICAL_SERVICES_READ = "medical_services:read";
    public static final String MEDICAL_SERVICES_WRITE = "medical_services:write";
    public static final String SERVICE_ORDERS_READ = "service_orders:read";
    public static final String SERVICE_ORDERS_WRITE = "service_orders:write";
    public static final String INVOICES_READ = "invoices:read";
    public static final String INVOICES_WRITE = "invoices:write";
    public static final String INVOICES_REPORT = "invoices:report";

    // Seeded by 037. Clinical staff draft first-aid guidance; doctors and
    // administrators review, publish and archive it (BR-31). What is published
    // is read by everybody, signed in or not, through /api/public.
    public static final String FIRST_AID_ARTICLES_READ = "first_aid_articles:read";
    public static final String FIRST_AID_ARTICLES_WRITE = "first_aid_articles:write";
    public static final String FIRST_AID_ARTICLES_REVIEW = "first_aid_articles:review";

    // Seeded by 038. Clinic staff answer their clinic's support chats (scoped
    // by row-level policy); a customer writes in their own. Guests chat
    // through /api/public/chat with a token instead of a permission.
    public static final String CONVERSATIONS_READ = "conversations:read";
    public static final String CONVERSATIONS_WRITE = "conversations:write";
    public static final String MY_CONVERSATIONS_READ = "my_conversations:read";
    public static final String MY_CONVERSATIONS_WRITE = "my_conversations:write";

    // Seeded by 040. Every role has its own notifications; the audit log is
    // the administrators' to search, and nobody's to change.
    public static final String NOTIFICATIONS_READ = "notifications:read";
    public static final String NOTIFICATIONS_WRITE = "notifications:write";
    public static final String AUDIT_LOGS_READ = "audit_logs:read";

    // A new business domain (patient, appointment, billing, ...) adds its own
    // three constants here, seeded in its own migration, the same way this
    // set was added on top of USERS_*/ROLES_*/PERMISSIONS_READ.

    private Permissions() {
    }
}
