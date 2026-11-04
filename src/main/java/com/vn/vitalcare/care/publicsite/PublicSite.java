package com.vn.vitalcare.care.publicsite;

import com.vn.vitalcare.care.billing.catalog.MedicalServiceCatalog;
import com.vn.vitalcare.care.clinic.service.ClinicService;
import com.vn.vitalcare.care.schedule.service.DoctorAvailabilityService;
import com.vn.vitalcare.care.staff.service.EmployeeService;
import com.vn.vitalcare.entity.Clinic;
import com.vn.vitalcare.entity.ServiceStatus;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What somebody who has not signed in may read about the clinics: where they
 * are and how to reach them, when they open, who sees patients, and what is
 * offered at what price. Only clinics that are operating are shown.
 */
@Service
@Transactional(readOnly = true)
public class PublicSite {

    private static final String OPERATING = "OPERATING";

    private final ClinicService clinics;
    private final EmployeeService employees;
    private final DoctorAvailabilityService availability;
    private final MedicalServiceCatalog catalog;
    private final NamedParameterJdbcTemplate jdbc;

    public PublicSite(ClinicService clinics, EmployeeService employees, DoctorAvailabilityService availability,
                      MedicalServiceCatalog catalog, NamedParameterJdbcTemplate jdbc) {
        this.clinics = clinics;
        this.employees = employees;
        this.availability = availability;
        this.catalog = catalog;
        this.jdbc = jdbc;
    }

    public List<PublicResponses.Clinic> clinics() {
        Map<UUID, List<PublicResponses.Opening>> hours = openingHours(null);
        return clinics.listAll().stream()
                .filter(clinic -> OPERATING.equals(clinic.getOperatingStatus()))
                .map(clinic -> new PublicResponses.Clinic(clinic.getClinicId(), clinic.getClinicName(),
                        clinic.getAddress(), clinic.getContactPhone(), clinic.getContactEmail(),
                        hours.getOrDefault(clinic.getClinicId(), List.of())))
                .toList();
    }

    /** One operating clinic with its doctors; any other is not found, as if it did not exist. */
    public PublicResponses.ClinicDetail clinic(UUID id) {
        Clinic clinic = clinics.get(id);
        if (!OPERATING.equals(clinic.getOperatingStatus())) {
            throw new ResourceNotFoundException("Clinic", id);
        }
        List<PublicResponses.Doctor> doctors = employees.doctorsOf(id).stream()
                .map(doctor -> new PublicResponses.Doctor(doctor.getId(), doctor.getUser().getFullName(),
                        doctor.getSpecialty(), doctor.getProfessionalTitle(),
                        availability.weekOf(doctor).stream()
                                .map(session -> session.getDayOfWeek().getValue())
                                .distinct()
                                .sorted()
                                .toList()))
                .toList();
        return new PublicResponses.ClinicDetail(clinic.getClinicId(), clinic.getClinicName(), clinic.getAddress(),
                clinic.getContactPhone(), clinic.getContactEmail(), openingHours(id).getOrDefault(id, List.of()),
                doctors);
    }

    /** The catalogue's active services, by category then name. */
    public List<PublicResponses.Service> services() {
        return catalog.list().stream()
                .filter(service -> service.getStatus() == ServiceStatus.ACTIVE)
                .sorted(Comparator.comparing((com.vn.vitalcare.entity.MedicalService service) -> service.getCategory())
                        .thenComparing(com.vn.vitalcare.entity.MedicalService::getName))
                .map(service -> new PublicResponses.Service(service.getId(), service.getName(),
                        service.getDescription(), service.getCategory(), service.getPrice(),
                        service.getTurnaroundMinutes()))
                .toList();
    }

    /**
     * Opening sessions by clinic, from {@code clinic_working_hours}: read
     * straight from the table, which nothing else maps yet.
     */
    private Map<UUID, List<PublicResponses.Opening>> openingHours(UUID clinicId) {
        String sql = "select clinic_id, day_of_week, open_time, close_time from clinic_working_hours "
                + "where deleted_at is null" + (clinicId == null ? "" : " and clinic_id = :clinicId")
                + " order by day_of_week, open_time";
        record Row(UUID clinicId, PublicResponses.Opening opening) {
        }
        List<Row> rows = jdbc.query(sql, clinicId == null ? Map.of() : Map.of("clinicId", clinicId),
                (rs, i) -> new Row(rs.getObject("clinic_id", UUID.class), new PublicResponses.Opening(
                        rs.getInt("day_of_week"),
                        rs.getTime("open_time").toLocalTime(),
                        rs.getTime("close_time").toLocalTime())));
        return rows.stream().collect(Collectors.groupingBy(Row::clinicId,
                Collectors.mapping(Row::opening, Collectors.toList())));
    }
}
