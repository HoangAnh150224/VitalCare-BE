package com.vn.vitalcare.care.schedule.controller;

import com.vn.vitalcare.care.clinic.service.ClinicService;
import com.vn.vitalcare.care.schedule.dto.DoctorAvailabilityResponse;
import com.vn.vitalcare.care.schedule.dto.WorkingHoursEntry;
import com.vn.vitalcare.care.schedule.dto.WorkingHoursRequest;
import com.vn.vitalcare.care.schedule.service.DoctorAvailabilityService;
import com.vn.vitalcare.care.schedule.service.DoctorScheduleService;
import com.vn.vitalcare.care.staff.service.ClinicScope;
import com.vn.vitalcare.care.staff.service.EmployeeService;
import com.vn.vitalcare.entity.Employee;
import com.vn.vitalcare.share.security.Permissions;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Doctors as somebody registering a visit meets them, and the week each one
 * works.
 */
@RestController
@RequestMapping("/api")
public class DoctorController {

    private final DoctorAvailabilityService availability;
    private final DoctorScheduleService schedules;
    private final EmployeeService employees;
    private final ClinicService clinics;
    private final ClinicScope clinicScope;

    public DoctorController(DoctorAvailabilityService availability,
                            DoctorScheduleService schedules,
                            EmployeeService employees,
                            ClinicService clinics,
                            ClinicScope clinicScope) {
        this.availability = availability;
        this.schedules = schedules;
        this.employees = employees;
        this.clinics = clinics;
        this.clinicScope = clinicScope;
    }

    /**
     * A clinic's doctors with the week each works and, when {@code date} is
     * given, the queue number that day would give. Open to anybody signed in,
     * a customer included: it is what the registration form chooses from.
     */
    @GetMapping("/clinics/{clinicId}/doctors")
    public List<DoctorAvailabilityResponse> doctors(
            @PathVariable UUID clinicId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        // An inactive clinic takes no new visits, so it offers no doctors to register with.
        if ("INACTIVE".equals(clinics.get(clinicId).getOperatingStatus())) {
            return List.of();
        }
        return employees.doctorsOf(clinicId).stream()
                .map(doctor -> DoctorAvailabilityResponse.from(
                        doctor,
                        availability.weekOf(doctor).stream().map(WorkingHoursEntry::from).toList(),
                        date == null ? null : availability.day(doctor, date),
                        null))
                .toList();
    }

    /**
     * The doctors of the caller's own clinic with the week each works — the
     * schedule screen's list. Empty for somebody tied to no clinic.
     */
    @GetMapping("/doctors")
    @PreAuthorize("hasAuthority('" + Permissions.DOCTOR_SCHEDULES_READ + "')")
    public List<DoctorAvailabilityResponse> ownClinicDoctors() {
        Predicate<Employee> manageable = schedules.manageableByCaller();
        return clinicScope.currentClinicId().map(employees::doctorsOf).orElse(List.of()).stream()
                .map(doctor -> DoctorAvailabilityResponse.from(
                        doctor,
                        availability.weekOf(doctor).stream().map(WorkingHoursEntry::from).toList(),
                        null,
                        manageable.test(doctor)))
                .toList();
    }

    @GetMapping("/doctors/{id}/working-hours")
    @PreAuthorize("hasAuthority('" + Permissions.DOCTOR_SCHEDULES_READ + "')")
    public List<WorkingHoursEntry> workingHours(@PathVariable Long id) {
        return availability.week(id).stream().map(WorkingHoursEntry::from).toList();
    }

    /** A doctor replaces their own week; a clinic administrator, any doctor's of the clinic. */
    @PutMapping("/doctors/{id}/working-hours")
    @PreAuthorize("hasAuthority('" + Permissions.DOCTOR_SCHEDULES_WRITE + "')")
    public List<WorkingHoursEntry> replaceWorkingHours(@PathVariable Long id,
                                                       @Valid @RequestBody WorkingHoursRequest request) {
        Employee doctor = schedules.manageableDoctor(id);
        return availability.replaceWeek(doctor, request.sessions()).stream().map(WorkingHoursEntry::from).toList();
    }
}
