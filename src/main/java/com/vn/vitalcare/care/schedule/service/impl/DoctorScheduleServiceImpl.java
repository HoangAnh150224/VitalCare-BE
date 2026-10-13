package com.vn.vitalcare.care.schedule.service.impl;

import com.vn.vitalcare.care.clinic.service.ClinicService;
import com.vn.vitalcare.care.schedule.dto.DoctorScheduleRequest;
import com.vn.vitalcare.care.schedule.repository.DoctorScheduleRepository;
import com.vn.vitalcare.care.schedule.repository.DoctorScheduleSpecifications;
import com.vn.vitalcare.care.schedule.service.DoctorScheduleService;
import com.vn.vitalcare.care.staff.service.EmployeeService;
import com.vn.vitalcare.entity.DoctorSchedule;
import com.vn.vitalcare.entity.Employee;
import com.vn.vitalcare.entity.EmployeeStatus;
import com.vn.vitalcare.entity.ScheduleStatus;
import com.vn.vitalcare.entity.StaffType;
import com.vn.vitalcare.identity.rowlevel.service.RowLevelSecurity;
import com.vn.vitalcare.share.data.BaseEntitySpecifications;
import com.vn.vitalcare.share.exception.ConflictException;
import com.vn.vitalcare.share.exception.FieldValidationException;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import com.vn.vitalcare.share.security.rowlevel.Action;
import com.vn.vitalcare.share.web.ListParams;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The {@link DoctorScheduleService} the application runs on. */
@Service
@Transactional(readOnly = true)
public class DoctorScheduleServiceImpl implements DoctorScheduleService {

    private static final Set<String> SORTABLE = Set.of("id", "workDate", "startTime", "status");

    private static final Sort DEFAULT_SORT = Sort.by(Sort.Order.asc("workDate"), Sort.Order.asc("startTime"));

    private final DoctorScheduleRepository repository;
    private final EmployeeService employeeService;
    private final ClinicService clinicService;
    private final RowLevelSecurity rowLevel;

    public DoctorScheduleServiceImpl(DoctorScheduleRepository repository,
                                     EmployeeService employeeService,
                                     ClinicService clinicService,
                                     RowLevelSecurity rowLevel) {
        this.repository = repository;
        this.employeeService = employeeService;
        this.clinicService = clinicService;
        this.rowLevel = rowLevel;
    }

    @Override
    public Page<DoctorSchedule> list(ListParams params) {
        return repository.findAll(
                rowLevel.scope(DoctorSchedule.class, Action.READ).and(DoctorScheduleSpecifications.from(params)),
                params.pageable(SORTABLE, DEFAULT_SORT));
    }

    @Override
    public List<DoctorSchedule> getMany(List<Long> ids) {
        return repository.findAll(Specification.allOf(
                rowLevel.byIds(DoctorSchedule.class, Action.READ, ids), BaseEntitySpecifications.notDeleted()));
    }

    @Override
    public DoctorSchedule get(Long id) {
        return find(id, Action.READ);
    }

    @Override
    @Transactional
    public DoctorSchedule create(DoctorScheduleRequest request) {
        Employee doctor = doctorFor(request.employeeId());
        if (doctor.getClinic() == null) {
            throw new ConflictException("That doctor's staff record is not tied to a clinic");
        }

        DoctorSchedule schedule = new DoctorSchedule();
        schedule.setEmployee(doctor);
        schedule.setClinic(doctor.getClinic());
        apply(schedule, request);

        DoctorSchedule saved = repository.persist(schedule);
        // WITH CHECK against the complete row, after the flush; see DeviceServiceImpl.register.
        repository.flush();
        rowLevel.checkWritable(saved);
        return saved;
    }

    @Override
    @Transactional
    public DoctorSchedule update(Long id, DoctorScheduleRequest request) {
        DoctorSchedule schedule = findOwn(id, Action.WRITE);
        apply(schedule, request);
        rowLevel.checkWritable(schedule);
        return repository.persist(schedule);
    }

    @Override
    @Transactional
    public DoctorSchedule delete(Long id) {
        DoctorSchedule schedule = findOwn(id, Action.DELETE);
        repository.remove(schedule);
        return schedule;
    }

    /**
     * Whose period a new row is: the caller's own when a doctor asks, the
     * doctor a clinic administrator names otherwise -- read through the
     * "employees" scope, so another clinic's doctor is not found.
     */
    private Employee doctorFor(Long employeeId) {
        Employee caller = employeeService.getForCurrentUser();
        if (caller.getStaffType() == StaffType.DOCTOR) {
            if (employeeId != null && !employeeId.equals(caller.getId())) {
                throw new FieldValidationException("employeeId", "A doctor manages their own schedule only");
            }
            return caller;
        }
        if (employeeId == null) {
            throw new FieldValidationException("employeeId", "A doctor is required");
        }
        Employee doctor = employeeService.get(employeeId);
        if (doctor.getStaffType() != StaffType.DOCTOR) {
            throw new FieldValidationException("employeeId", "Only a doctor has a schedule");
        }
        if (doctor.getStatus() != EmployeeStatus.ACTIVE) {
            throw new FieldValidationException("employeeId", "That doctor is no longer active");
        }
        return doctor;
    }

    /**
     * One row the caller may change: inside their scope, and their own when
     * they are a doctor. The second rule is here, not in a policy, because a
     * policy compares a row's columns and "mine" is a row of another table.
     */
    private DoctorSchedule findOwn(Long id, Action action) {
        DoctorSchedule schedule = find(id, action);
        if (!changeableByCaller().test(schedule)) {
            throw new ResourceNotFoundException("Doctor schedule", id);
        }
        return schedule;
    }

    @Override
    public Predicate<DoctorSchedule> changeableByCaller() {
        Employee caller;
        try {
            caller = employeeService.getForCurrentUser();
        } catch (ConflictException e) {
            // No staff record: not a doctor, so the scope is the whole answer.
            return schedule -> true;
        }
        if (caller.getStaffType() != StaffType.DOCTOR) {
            return schedule -> true;
        }
        Long own = caller.getId();
        return schedule -> own.equals(schedule.getEmployee().getId());
    }

    private void apply(DoctorSchedule schedule, DoctorScheduleRequest request) {
        if (!request.endTime().isAfter(request.startTime())) {
            throw new FieldValidationException("endTime", "The end time must be after the start time");
        }
        // A past period keeps its date -- its note or kind can still be corrected -- but nothing moves into the past.
        if (!request.workDate().equals(schedule.getWorkDate()) && request.workDate().isBefore(clinicService.today())) {
            throw new FieldValidationException("workDate", "The date cannot be in the past");
        }
        // ponytail: checked, not locked -- two saves at the same instant could
        // both pass. An exclusion constraint on (employee, date, time range) is
        // the fix if schedules are ever edited concurrently.
        if (repository.exists(DoctorScheduleSpecifications.overlapping(schedule.getEmployee().getId(),
                request.workDate(), request.startTime(), request.endTime(), schedule.getId()))) {
            throw new ConflictException("That doctor already has a period overlapping this one on that day");
        }
        schedule.setWorkDate(request.workDate());
        schedule.setStartTime(request.startTime());
        schedule.setEndTime(request.endTime());
        schedule.setStatus(request.status() == null ? ScheduleStatus.WORKING : request.status());
        schedule.setNote(blankToNull(request.note()));
    }

    /** One row through the caller's scope for {@code action}; outside it is a 404. See DeviceServiceImpl. */
    private DoctorSchedule find(Long id, Action action) {
        return repository.findOne(Specification.allOf(
                        rowLevel.byId(DoctorSchedule.class, action, id), BaseEntitySpecifications.notDeleted()))
                .orElseThrow(() -> new ResourceNotFoundException("Doctor schedule", id));
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
