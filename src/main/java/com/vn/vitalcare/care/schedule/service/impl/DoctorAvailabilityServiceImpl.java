package com.vn.vitalcare.care.schedule.service.impl;

import com.vn.vitalcare.care.appointment.repository.AppointmentRepository;
import com.vn.vitalcare.care.clinic.service.ClinicService;
import com.vn.vitalcare.care.schedule.dto.DayAvailability;
import com.vn.vitalcare.care.schedule.dto.WorkingHoursEntry;
import com.vn.vitalcare.care.schedule.repository.DoctorScheduleRepository;
import com.vn.vitalcare.care.schedule.repository.DoctorScheduleSpecifications;
import com.vn.vitalcare.care.schedule.repository.DoctorWorkingHoursRepository;
import com.vn.vitalcare.care.schedule.service.DoctorAvailabilityService;
import com.vn.vitalcare.care.schedule.service.Turn;
import com.vn.vitalcare.care.staff.service.ClinicScope;
import com.vn.vitalcare.care.staff.service.EmployeeService;
import com.vn.vitalcare.entity.DoctorSchedule;
import com.vn.vitalcare.entity.DoctorWorkingHours;
import com.vn.vitalcare.entity.Employee;
import com.vn.vitalcare.share.exception.FieldValidationException;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The {@link DoctorAvailabilityService} the application runs on. */
@Service
@Transactional(readOnly = true)
public class DoctorAvailabilityServiceImpl implements DoctorAvailabilityService {

    private final DoctorWorkingHoursRepository hours;
    private final DoctorScheduleRepository blocked;
    private final AppointmentRepository appointments;
    private final EmployeeService employeeService;
    private final ClinicService clinicService;
    private final ClinicScope clinicScope;
    private final Clock clock;

    public DoctorAvailabilityServiceImpl(DoctorWorkingHoursRepository hours,
                                         DoctorScheduleRepository blocked,
                                         AppointmentRepository appointments,
                                         EmployeeService employeeService,
                                         ClinicService clinicService,
                                         ClinicScope clinicScope,
                                         Clock clock) {
        this.hours = hours;
        this.blocked = blocked;
        this.appointments = appointments;
        this.employeeService = employeeService;
        this.clinicService = clinicService;
        this.clinicScope = clinicScope;
        this.clock = clock;
    }

    @Override
    public List<DoctorWorkingHours> week(Long employeeId) {
        Employee doctor = employeeService.doctor(employeeId);
        // Staff read their own clinic's doctors; somebody tied to no clinic reads any.
        if (clinicScope.currentClinicId().filter(own -> !own.equals(doctor.getClinicId())).isPresent()) {
            throw new ResourceNotFoundException("Doctor", employeeId);
        }
        return weekOf(doctor);
    }

    @Override
    public List<DoctorWorkingHours> weekOf(Employee doctor) {
        return hours.findWeek(doctor.getId());
    }

    @Override
    @Transactional
    public List<DoctorWorkingHours> replaceWeek(Employee doctor, List<WorkingHoursEntry> sessions) {
        validate(sessions);

        // The lock a registration takes: a number is never counted against a
        // week that is half replaced, and two saves cannot both insert.
        employeeService.lockDoctor(doctor.getId());
        hours.deleteWeek(doctor.getId());
        hours.flush();

        List<DoctorWorkingHours> saved = new ArrayList<>();
        for (WorkingHoursEntry entry : sessions) {
            saved.add(hours.save(new DoctorWorkingHours(doctor, DayOfWeek.of(entry.dayOfWeek()), entry.startTime(),
                    entry.endTime(), entry.visitMinutes())));
        }
        saved.sort(Comparator.comparing(DoctorWorkingHours::getDayOfWeek)
                .thenComparing(DoctorWorkingHours::getStartTime));
        return saved;
    }

    @Override
    public List<Turn> turns(Employee doctor, LocalDate date) {
        List<DoctorWorkingHours> sessions = hours.findDay(doctor.getId(), (short) date.getDayOfWeek().getValue());
        if (sessions.isEmpty()) {
            return List.of();
        }
        List<DoctorSchedule> off = blocked.findAll(DoctorScheduleSpecifications.onDay(doctor.getId(), date));

        List<Turn> turns = new ArrayList<>();
        for (DoctorWorkingHours session : sessions) {
            // A remainder too short for a whole visit is left unused.
            LocalTime start = session.getStartTime();
            while (!start.plusMinutes(session.getVisitMinutes()).isAfter(session.getEndTime())
                    // plusMinutes wraps at midnight; a session never spans it.
                    && start.plusMinutes(session.getVisitMinutes()).isAfter(start)) {
                LocalTime end = start.plusMinutes(session.getVisitMinutes());
                LocalTime from = start;
                boolean isBlocked = off.stream().anyMatch(period ->
                        period.getStartTime().isBefore(end) && period.getEndTime().isAfter(from));
                if (!isBlocked) {
                    turns.add(new Turn(start, end));
                }
                start = end;
            }
        }
        return turns;
    }

    @Override
    public Optional<Turn> turnFor(List<Turn> turns, LocalDate date, int queueNumber, long waiting) {
        int firstAhead = firstAhead(turns, date);
        if (turns.size() - firstAhead <= waiting) {
            return Optional.empty();
        }
        int position = firstAhead == 0 ? queueNumber - 1 : firstAhead + (int) waiting;
        return Optional.of(turns.get(Math.min(position, turns.size() - 1)));
    }

    /** The index of the first turn that has not started yet; {@code turns.size()} when the day is over. */
    private int firstAhead(List<Turn> turns, LocalDate date) {
        LocalDateTime now = LocalDateTime.now(clock.withZone(clinicService.timeZone()));
        int index = 0;
        while (index < turns.size() && !LocalDateTime.of(date, turns.get(index).start()).isAfter(now)) {
            index++;
        }
        return index;
    }

    @Override
    public DayAvailability day(Employee doctor, LocalDate date) {
        List<Turn> turns = turns(doctor, date);
        long taken = appointments.countWaiting(doctor.getId(), date);
        // Out of the turns still ahead: a day half gone has only its other half to give.
        long remaining = Math.max(0, turns.size() - firstAhead(turns, date) - taken);
        int next = appointments.maxQueueNumber(doctor.getId(), date) + 1;

        boolean open = !date.isBefore(clinicService.today()) && clinicService.isWithinBookingWindow(date);
        Optional<Turn> turn = open ? turnFor(turns, date, next, taken) : Optional.empty();
        return new DayAvailability(
                date,
                !turns.isEmpty(),
                turns.size(),
                taken,
                remaining,
                turn.isPresent() ? next : null,
                turn.map(Turn::start).orElse(null),
                turn.map(Turn::end).orElse(null),
                turn.isPresent());
    }

    private static void validate(List<WorkingHoursEntry> sessions) {
        for (WorkingHoursEntry entry : sessions) {
            if (!entry.endTime().isAfter(entry.startTime())) {
                throw new FieldValidationException("sessions", "A session must end after it starts");
            }
        }
        List<WorkingHoursEntry> sorted = new ArrayList<>(sessions);
        sorted.sort(Comparator.comparing(WorkingHoursEntry::dayOfWeek).thenComparing(WorkingHoursEntry::startTime));
        for (int i = 1; i < sorted.size(); i++) {
            WorkingHoursEntry previous = sorted.get(i - 1);
            WorkingHoursEntry current = sorted.get(i);
            if (previous.dayOfWeek().equals(current.dayOfWeek())
                    && current.startTime().isBefore(previous.endTime())) {
                throw new FieldValidationException("sessions", "Two sessions on the same day overlap");
            }
        }
    }
}
