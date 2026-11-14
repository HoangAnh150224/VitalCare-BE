package com.vn.vitalcare.care.staff.service;

import com.vn.vitalcare.share.exception.FieldValidationException;
import java.util.Set;

/**
 * The fixed lists a member of staff's specialty, title and position are picked
 * from. The value stored is the label itself; the staff form offers the same
 * lists, so the two must change together.
 */
public final class StaffProfileOptions {

    public static final Set<String> SPECIALTIES = Set.of(
            "Tim mạch", "Hô hấp", "Tim mạch nhi", "Hô hấp nhi",
            "Phẫu thuật tim mạch", "Phẫu thuật lồng ngực");

    public static final Set<String> PROFESSIONAL_TITLES = Set.of(
            "Bác sĩ", "Bác sĩ chuyên khoa I", "Bác sĩ chuyên khoa II",
            "Thạc sĩ, Bác sĩ", "Tiến sĩ, Bác sĩ", "Phó giáo sư, Tiến sĩ", "Giáo sư, Tiến sĩ",
            "Điều dưỡng", "Kỹ thuật viên", "Lễ tân");

    public static final Set<String> CLINIC_POSITIONS = Set.of(
            "Trưởng khoa", "Phó trưởng khoa", "Bác sĩ điều trị", "Bác sĩ phẫu thuật",
            "Điều dưỡng trưởng", "Điều dưỡng", "Kỹ thuật viên", "Lễ tân", "Thu ngân");

    private StaffProfileOptions() {
    }

    /**
     * Rejects a value that is not on the list. Empty is always allowed, and so
     * is the value the record already holds — one typed in before the lists
     * existed stays until somebody picks another.
     */
    public static void check(String field, String value, String current, Set<String> allowed) {
        if (value == null || value.isBlank()) {
            return;
        }
        String trimmed = value.trim();
        if (!allowed.contains(trimmed) && !trimmed.equals(current)) {
            throw new FieldValidationException(field, "Giá trị không nằm trong danh sách cho phép");
        }
    }
}
