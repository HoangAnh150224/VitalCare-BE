package com.vn.vitalcare.care.clinic.service.impl;

import com.vn.vitalcare.care.appointment.repository.AppointmentRepository;
import com.vn.vitalcare.care.clinic.ClinicProperties;
import com.vn.vitalcare.care.clinic.dto.WorkingHoursEntry;
import com.vn.vitalcare.care.clinic.repository.ClinicRepository;
import com.vn.vitalcare.care.clinic.repository.ClinicWorkingHoursRepository;
import com.vn.vitalcare.care.clinic.service.ClinicScheduleService;
import com.vn.vitalcare.care.clinic.service.Slot;
import com.vn.vitalcare.entity.AppointmentStatus;
import com.vn.vitalcare.entity.Clinic;
import com.vn.vitalcare.entity.ClinicWorkingHours;
import com.vn.vitalcare.share.exception.FieldValidationException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The {@link ClinicScheduleService} the application runs on. */
@Service
@Transactional(readOnly = true)
public class ClinicScheduleServiceImpl implements ClinicScheduleService {

    /** What occupies a place in a slot. A cancelled appointment gives it back. */
    private static final EnumSet<AppointmentStatus> OCCUPYING =
            EnumSet.of(AppointmentStatus.SCHEDULED, AppointmentStatus.CHECKED_IN);

    private final ClinicRepository clinics;
    private final ClinicWorkingHoursRepository hours;
    private final AppointmentRepository appointments;
    private final ClinicProperties properties;
    private final Clock clock;

    public ClinicScheduleServiceImpl(ClinicRepository clinics,
                                     ClinicWorkingHoursRepository hours,
                                     AppointmentRepository appointments,
                                     ClinicProperties properties,
                                     Clock clock) {
        this.clinics = clinics;
        this.hours = hours;
        this.appointments = appointments;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public List<ClinicWorkingHours> week(Clinic clinic) {
        return hours.findWeek(clinic.getClinicId());
    }

    @Override
    public List<Slot> slotsFor(Clinic clinic, LocalDate date) {
        List<Slot> templates = cut(clinic, date);
        if (templates.isEmpty()) {
            return templates;
        }

        Map<LocalTime, Long> booked = new HashMap<>();
        appointments.countBySlot(clinic.getClinicId(), date, OCCUPYING)
                .forEach(row -> booked.put(row.getStartTime(), row.getBooked()));

        LocalDateTime now = LocalDateTime.now(clock.withZone(properties.timeZone()));
        List<Slot> slots = new ArrayList<>(templates.size());
        for (Slot template : templates) {
            long taken = booked.getOrDefault(template.startTime(), 0L);
            Slot.State state;
            if (!LocalDateTime.of(date, template.startTime()).isAfter(now)) {
                state = Slot.State.PAST;
            } else if (taken >= template.capacity()) {
                state = Slot.State.FULL;
            } else {
                state = Slot.State.AVAILABLE;
            }
            slots.add(new Slot(template.startTime(), template.endTime(), template.capacity(), taken, state));
        }
        return slots;
    }

    @Override
    public Optional<Slot> slotStartingAt(Clinic clinic, LocalDate date, LocalTime start) {
        return cut(clinic, date).stream().filter(slot -> slot.startTime().equals(start)).findFirst();
    }

    @Override
    public boolean isOpenOn(Clinic clinic, LocalDate date) {
        return !hours.findDay(clinic.getClinicId(), (short) date.getDayOfWeek().getValue()).isEmpty();
    }

    @Override
    public long bookedIn(Clinic clinic, LocalDate date, LocalTime start) {
        return appointments.countBySlot(clinic.getClinicId(), date, OCCUPYING).stream()
                .filter(row -> row.getStartTime().equals(start))
                .mapToLong(AppointmentRepository.SlotCount::getBooked)
                .findFirst()
                .orElse(0L);
    }

    @Override
    @Transactional
    public List<ClinicWorkingHours> replaceWeek(Clinic clinic, List<WorkingHoursEntry> sessions) {
        validate(sessions);

        // The same lock a booking takes. Two saves at once would otherwise
        // each delete only the rows they could see and both insert, leaving
        // the week doubled; and a booking counted against hours being
        // replaced mid-way would be counted against the wrong slots.
        clinics.findForUpdate(clinic.getClinicId());

        hours.deleteWeek(clinic.getClinicId());
        hours.flush();

        List<ClinicWorkingHours> saved = new ArrayList<>();
        for (WorkingHoursEntry entry : sessions) {
            saved.add(hours.save(new ClinicWorkingHours(
                    clinic,
                    DayOfWeek.of(entry.dayOfWeek()),
                    entry.openTime(),
                    entry.closeTime(),
                    entry.slotMinutes(),
                    entry.capacityPerSlot())));
        }
        saved.sort(Comparator.comparing(ClinicWorkingHours::getDayOfWeek)
                .thenComparing(ClinicWorkingHours::getOpenTime));
        return saved;
    }

    private static void validate(List<WorkingHoursEntry> sessions) {
        for (WorkingHoursEntry entry : sessions) {
            if (!entry.closeTime().isAfter(entry.openTime())) {
                throw new FieldValidationException("sessions", "A session must close after it opens");
            }
        }
        List<WorkingHoursEntry> sorted = new ArrayList<>(sessions);
        sorted.sort(Comparator.comparing(WorkingHoursEntry::dayOfWeek)
                .thenComparing(WorkingHoursEntry::openTime));
        for (int i = 1; i < sorted.size(); i++) {
            WorkingHoursEntry previous = sorted.get(i - 1);
            WorkingHoursEntry current = sorted.get(i);
            if (previous.dayOfWeek().equals(current.dayOfWeek())
                    && current.openTime().isBefore(previous.closeTime())) {
                throw new FieldValidationException("sessions", "Two sessions on the same day overlap");
            }
        }
    }

    private List<Slot> cut(Clinic clinic, LocalDate date) {
        List<Slot> slots = new ArrayList<>();
        for (ClinicWorkingHours session : hours.findDay(clinic.getClinicId(), (short) date.getDayOfWeek().getValue())) {
            LocalTime start = session.getOpenTime();
            while (true) {
                LocalTime end = start.plusMinutes(session.getSlotMinutes());
                // A slot ending past the close -- or wrapping past midnight,
                // which would make "end" earlier than "start" -- is not a slot.
                if (end.isAfter(session.getCloseTime()) || !end.isAfter(start)) {
                    break;
                }
                slots.add(new Slot(start, end, session.getCapacityPerSlot(), 0, Slot.State.AVAILABLE));
                start = end;
            }
        }
        slots.sort(Comparator.comparing(Slot::startTime));
        return slots;
    }
}
