package com.vn.vitalcare.care.publicsite;

import com.vn.vitalcare.entity.ServiceCategory;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * What the public site answers. Written apart from the staff DTOs on purpose:
 * nothing here may grow a field somebody not signed in should not see (staff
 * counts, contact persons, internal codes) by a staff screen needing it.
 */
public final class PublicResponses {

    private PublicResponses() {
    }

    /** One opening session; ISO weekday, 1 = Monday … 7 = Sunday. */
    public record Opening(int dayOfWeek, LocalTime openTime, LocalTime closeTime) {
    }

    public record Clinic(
            UUID id,
            String name,
            String address,
            String phone,
            String email,
            List<Opening> openingHours) {
    }

    /** A doctor as a visitor reads them: name, field, and the weekdays they see patients. */
    public record Doctor(Long id, String fullName, String specialty, String professionalTitle, List<Integer> workingDays) {
    }

    public record ClinicDetail(
            UUID id,
            String name,
            String address,
            String phone,
            String email,
            List<Opening> openingHours,
            List<Doctor> doctors) {
    }

    public record Service(
            Long id,
            String name,
            String description,
            ServiceCategory category,
            BigDecimal price,
            Integer turnaroundMinutes) {
    }
}
