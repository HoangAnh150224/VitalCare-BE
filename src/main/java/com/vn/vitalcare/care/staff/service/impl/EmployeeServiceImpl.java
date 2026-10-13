package com.vn.vitalcare.care.staff.service.impl;

import com.vn.vitalcare.care.assignment.repository.MonitoringAssignmentRepository;
import com.vn.vitalcare.care.clinic.service.ClinicService;
import com.vn.vitalcare.care.staff.dto.EmployeeCreateRequest;
import com.vn.vitalcare.care.staff.dto.EmployeePatchRequest;
import com.vn.vitalcare.care.staff.repository.EmployeeRepository;
import com.vn.vitalcare.care.staff.repository.EmployeeSpecifications;
import com.vn.vitalcare.care.staff.service.ClinicScope;
import com.vn.vitalcare.care.staff.service.EmployeeService;
import com.vn.vitalcare.entity.Clinic;
import com.vn.vitalcare.entity.Employee;
import com.vn.vitalcare.entity.EmployeeStatus;
import com.vn.vitalcare.entity.MonitoringAssignment;
import com.vn.vitalcare.identity.rowlevel.service.RowLevelSecurity;
import com.vn.vitalcare.identity.user.entity.User;
import com.vn.vitalcare.identity.user.service.UserService;
import com.vn.vitalcare.share.data.BaseEntitySpecifications;
import com.vn.vitalcare.share.exception.ConflictException;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import com.vn.vitalcare.share.security.AuthoritiesChanged;
import com.vn.vitalcare.share.security.CurrentUser;
import com.vn.vitalcare.share.security.rowlevel.Action;
import com.vn.vitalcare.share.web.ListParams;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The {@link EmployeeService} the application runs on. */
@Service
@Transactional(readOnly = true)
public class EmployeeServiceImpl implements EmployeeService {

    private static final Set<String> SORTABLE = Set.of("id", "employeeCode", "staffType", "status", "createdAt");

    private static final Sort DEFAULT_SORT = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final EmployeeRepository repository;
    private final MonitoringAssignmentRepository careTeams;
    private final UserService userService;
    private final ClinicService clinicService;
    // Only to pick the clinic a new member of staff joins when the request
    // names none; which clinics' staff the caller reaches, and may add to, is
    // the "employees" row-level policy's.
    private final ClinicScope clinicScope;
    private final RowLevelSecurity rowLevel;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public EmployeeServiceImpl(EmployeeRepository repository,
                               MonitoringAssignmentRepository careTeams,
                               UserService userService,
                               ClinicService clinicService,
                               ClinicScope clinicScope,
                               RowLevelSecurity rowLevel,
                               ApplicationEventPublisher events,
                               Clock clock) {
        this.repository = repository;
        this.careTeams = careTeams;
        this.userService = userService;
        this.clinicService = clinicService;
        this.clinicScope = clinicScope;
        this.rowLevel = rowLevel;
        this.events = events;
        this.clock = clock;
    }

    @Override
    public Page<Employee> list(ListParams params) {
        return repository.findAll(
                rowLevel.scope(Employee.class, Action.READ).and(EmployeeSpecifications.from(params)),
                params.pageable(SORTABLE, DEFAULT_SORT));
    }

    @Override
    public List<Employee> getMany(List<Long> ids) {
        return repository.findAll(Specification.allOf(
                rowLevel.byIds(Employee.class, Action.READ, ids), BaseEntitySpecifications.notDeleted()));
    }

    @Override
    public Employee get(Long id) {
        return find(id, Action.READ);
    }

    @Override
    public Employee getForCurrentUser() {
        Long userId = CurrentUser.id().orElseThrow(() -> new ConflictException("Not signed in"));
        return repository.findByUserIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new ConflictException("This account has no staff profile"));
    }

    @Override
    @Transactional
    public Employee create(EmployeeCreateRequest request) {
        Employee employee = new Employee();
        employee.setStaffType(request.staffType());
        employee.setSpecialty(blankToNull(request.specialty()));
        employee.setProfessionalTitle(blankToNull(request.professionalTitle()));
        employee.setLicenseNo(blankToNull(request.licenseNo()));
        employee.setClinicPosition(blankToNull(request.clinicPosition()));
        employee.setClinic(clinicFor(request.clinicId()));

        User user = userService.createStaffAccount(
                request.phone(), request.password(), request.fullName(), request.staffType().roleCode());
        employee.setUser(user);

        Employee saved = repository.persist(employee);
        // WITH CHECK against the complete row, as the framework does it: after
        // the flush, when the id and audit stamps exist. A clinic outside the
        // caller's scope throws here, and the rollback takes the sign-in
        // account created above with it -- it is the same transaction.
        repository.flush();
        rowLevel.checkWritable(saved);
        // Derived from the id, like the customer code.
        saved.setEmployeeCode("NV%06d".formatted(saved.getId()));
        // Their clinic is what their own policies compare against, and it is
        // read into the authority cache: drop anything cached for the account.
        events.publishEvent(new AuthoritiesChanged.ForUser(user.getId()));
        return saved;
    }

    @Override
    @Transactional
    public Employee lockForUpdate(Long id) {
        // Read scope: a care-team assignment locks the member of staff to stop
        // them leaving mid-assignment, but does not change them.
        return lock(id, Action.READ);
    }

    @Override
    @Transactional
    public Employee update(Long id, EmployeePatchRequest request) {
        // USING, on the state as it stands: through the write scope, so a
        // member of staff the caller may see but not edit is a 404. A status
        // change takes the lock a care-team assignment takes, so leaving and
        // being assigned cannot interleave.
        Employee employee = request.status() == null ? find(id, Action.WRITE) : lock(id, Action.WRITE);

        if (request.specialty() != null) {
            employee.setSpecialty(blankToNull(request.specialty()));
        }
        if (request.professionalTitle() != null) {
            employee.setProfessionalTitle(blankToNull(request.professionalTitle()));
        }
        if (request.licenseNo() != null) {
            employee.setLicenseNo(blankToNull(request.licenseNo()));
        }
        if (request.clinicPosition() != null) {
            employee.setClinicPosition(blankToNull(request.clinicPosition()));
        }
        if (request.status() != null && request.status() != employee.getStatus()) {
            changeStatus(employee, request.status());
        }
        // WITH CHECK, on the state this save leaves behind.
        rowLevel.checkWritable(employee);
        return repository.persist(employee);
    }

    /**
     * Somebody leaving or coming back.
     *
     * <p>Leaving ends every care-team assignment they hold, so no patient is
     * left with a carer who will never answer, and stops their account signing
     * in. The devices their patients wear stay where they are: a device is the
     * patient's, not the carer's.
     */
    private void changeStatus(Employee employee, EmployeeStatus status) {
        employee.setStatus(status);
        if (status == EmployeeStatus.INACTIVE) {
            OffsetDateTime now = OffsetDateTime.now(clock);
            Long actor = CurrentUser.id().orElse(null);
            for (MonitoringAssignment assignment :
                    careTeams.findByEmployeeIdAndUnassignedAtIsNullAndDeletedAtIsNullOrderByAssignedAtDesc(
                            employee.getId())) {
                assignment.end(actor, now);
            }
            userService.setSignInAllowed(employee.getUser().getId(), false);
        } else {
            userService.setSignInAllowed(employee.getUser().getId(), true);
        }
    }

    /**
     * Where a new member of staff works: the clinic asked for, else the
     * creator's own, else the first one. Whether the creator may add staff
     * there is the write policy's to say; the caller checks it.
     */
    private Clinic clinicFor(UUID requested) {
        if (requested != null) {
            return clinicService.get(requested);
        }
        return clinicScope.currentClinicId()
                .map(clinicService::get)
                .or(() -> clinicService.listAll().stream().findFirst())
                .orElse(null);
    }

    /**
     * One row through the caller's scope for {@code action}, in the query
     * itself. Outside it is a 404, indistinguishable from a row that does not
     * exist -- deliberately, as in the framework's {@code TaskService}.
     */
    private Employee find(Long id, Action action) {
        return repository.findOne(Specification.allOf(
                        rowLevel.byId(Employee.class, action, id), BaseEntitySpecifications.notDeleted()))
                .orElseThrow(() -> new ResourceNotFoundException("Employee", id));
    }

    /**
     * The row under a pessimistic lock, then the same scope check as
     * {@link #find}. The lock query takes no specification, so the scope is
     * asked of the database separately, after the lock is held -- the row it
     * answers about is the one this transaction now owns.
     */
    private Employee lock(Long id, Action action) {
        Employee row = repository.findForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Employee", id));
        if (!repository.exists(rowLevel.byId(Employee.class, action, id))) {
            throw new ResourceNotFoundException("Employee", id);
        }
        return row;
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
