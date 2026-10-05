package com.vn.vitalcare.care.clinic.controller;

import com.vn.vitalcare.care.clinic.dto.ClinicResponse;
import com.vn.vitalcare.care.clinic.dto.DaySlotsResponse;
import com.vn.vitalcare.care.clinic.dto.SlotResponse;
import com.vn.vitalcare.care.clinic.dto.WorkingHoursEntry;
import com.vn.vitalcare.care.clinic.dto.WorkingHoursRequest;
import com.vn.vitalcare.care.clinic.service.ClinicScheduleService;
import com.vn.vitalcare.care.clinic.service.ClinicService;
import com.vn.vitalcare.entity.Clinic;
import com.vn.vitalcare.share.security.Permissions;
import com.vn.vitalcare.share.web.ListResponse;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/clinics} — where to book, when it is open, and what is free.
 *
 * <p>Reading needs no permission beyond being signed in: which clinics exist,
 * their hours and their free slots are what a clinic puts on its front door,
 * and every booking screen needs them. Changing the hours needs
 * {@code clinics:write}.
 */
@RestController
@RequestMapping("/api/clinics")
public class ClinicController {

    private final ClinicService service;
    private final ClinicScheduleService schedule;

    public ClinicController(ClinicService service, ClinicScheduleService schedule) {
        this.service = service;
        this.schedule = schedule;
    }

    /** Unpaged: the list is short and every caller wants all of it. */
    @GetMapping
    public ResponseEntity<List<ClinicResponse>> list() {
        List<ClinicResponse> rows = service.listAll().stream().map(ClinicResponse::from).toList();
        return ListResponse.of(rows, rows.size());
    }

    @GetMapping("/{id}")
    public ClinicResponse get(@PathVariable UUID id) {
        return ClinicResponse.from(service.get(id));
    }

    @GetMapping("/{id}/working-hours")
    public List<WorkingHoursEntry> workingHours(@PathVariable UUID id) {
        return schedule.week(service.get(id)).stream().map(WorkingHoursEntry::from).toList();
    }

    /** Replaces the whole week. Appointments already booked keep their times. */
    @PutMapping("/{id}/working-hours")
    @PreAuthorize("hasAuthority('" + Permissions.CLINICS_WRITE + "')")
    public List<WorkingHoursEntry> replaceWorkingHours(@PathVariable UUID id,
                                                       @Valid @RequestBody WorkingHoursRequest request) {
        return schedule.replaceWeek(service.get(id), request.sessions()).stream()
                .map(WorkingHoursEntry::from)
                .toList();
    }

    /** One day's slots and how full each one is — what the booking grid draws. */
    @GetMapping("/{id}/slots")
    public DaySlotsResponse slots(@PathVariable UUID id,
                                  @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        Clinic clinic = service.get(id);
        boolean bookable = service.isWithinBookingWindow(date);
        List<SlotResponse> slots = bookable
                ? schedule.slotsFor(clinic, date).stream().map(SlotResponse::from).toList()
                : List.of();
        return new DaySlotsResponse(date, schedule.isOpenOn(clinic, date), bookable, service.lastBookableDate(), slots);
    }
}
