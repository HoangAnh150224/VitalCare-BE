package com.vn.vitalcare.care.staff.service.impl;

import java.util.Optional;
import com.vn.vitalcare.entity.StaffType;
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
import com.vn.vitalcare.share.exception.FieldValidationException;
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
    private final com.vn.vitalcare.care.staff.repository.StaffMembershipRepository memberships;
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
                               com.vn.vitalcare.care.staff.repository.StaffMembershipRepository memberships,
                               UserService userService,
                               ClinicService clinicService,
                               ClinicScope clinicScope,
                               RowLevelSecurity rowLevel,
                               ApplicationEventPublisher events,
                               Clock clock) {
        this.repository = repository;
        this.careTeams = careTeams;
        this.memberships = memberships;
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
        boolean clinicAdministrator = request.staffType() == StaffType.CLINIC_ADMIN;
        if (clinicAdministrator && !CurrentUser.has(com.vn.vitalcare.share.security.Permissions.CLINICS_WRITE)) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Only the System Administrator can create a Clinic Administrator");
        }
        if (!clinicAdministrator && !CurrentUser.has(com.vn.vitalcare.share.security.Permissions.EMPLOYEES_WRITE)) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Only a Clinic Administrator can create clinic staff");
        }
        if (clinicAdministrator && request.clinicId() == null) {
            throw new FieldValidationException("clinicId", "Choose the clinic this administrator will manage");
        }

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
        // System administrators may create only clinic administrators. Their
        // system scope is checked by clinics:write and the required clinic id;
        // employee row-level write access remains clinic-admin-only.
        if (!clinicAdministrator) {
            rowLevel.checkWritable(saved);
        }
        // Derived from the id, like the customer code.
        saved.setEmployeeCode("NV%06d".formatted(saved.getId()));
        // Joining is the first membership.
        memberships.save(new com.vn.vitalcare.entity.StaffMembership(saved, saved.getClinic(), OffsetDateTime.now(clock)));
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
        changeStatus(employee, status, "Nghỉ việc");
    }

    private void changeStatus(Employee employee, EmployeeStatus status, String reason) {
        employee.setStatus(status);
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (status == EmployeeStatus.INACTIVE) {
            endCareTeams(employee, now);
            memberships.findActive(employee.getId()).ifPresent(m -> m.end(now, reason));
            userService.setSignInAllowed(employee.getUser().getId(), false);
        } else {
            // Coming back is a new membership of the clinic they belong to.
            if (memberships.findActive(employee.getId()).isEmpty() && employee.getClinic() != null) {
                memberships.save(new com.vn.vitalcare.entity.StaffMembership(employee, employee.getClinic(), now));
            }
            userService.setSignInAllowed(employee.getUser().getId(), true);
        }
    }

    private void endCareTeams(Employee employee, OffsetDateTime now) {
        Long actor = CurrentUser.id().orElse(null);
        for (MonitoringAssignment assignment :
                careTeams.findByEmployeeIdAndUnassignedAtIsNullAndDeletedAtIsNullOrderByAssignedAtDesc(employee.getId())) {
            assignment.end(actor, now);
        }
    }

    @Override
    public List<com.vn.vitalcare.entity.StaffMembership> memberships(Long id) {
        find(id, Action.READ);
        return memberships.findHistory(id);
    }

    /**
     * Their membership here ends and one at the other clinic starts. The
     * patients they looked after here are not theirs there: their care teams
     * end, and their working sessions leave this clinic's rooms. Refused while
     * they have visits still to see here.
     */
    @Override
    @Transactional
    public Employee transfer(Long id, java.util.UUID clinicId, String reason) {
        Employee employee = lock(id, Action.WRITE);
        if (employee.getStatus() != EmployeeStatus.ACTIVE) {
            throw new ConflictException("Only somebody still on staff can move to another clinic");
        }
        com.vn.vitalcare.entity.Clinic target;
        try {
            target = clinicService.get(clinicId);
        } catch (ResourceNotFoundException e) {
            throw new com.vn.vitalcare.share.exception.FieldValidationException("clinicId", "Pick one of the clinics");
        }
        if (employee.getClinic() != null && target.getClinicId().equals(employee.getClinic().getClinicId())) {
            throw new com.vn.vitalcare.share.exception.FieldValidationException("clinicId", "They already work at this clinic");
        }
        if (repository.countUpcomingVisits(id, java.time.LocalDate.now(clock.withZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh")))) > 0) {
            throw new ConflictException("They still have visits to see at this clinic; hand them over first");
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        String why = reason == null || reason.isBlank() ? "Chuyển cơ sở" : reason.trim();
        memberships.findActive(id).ifPresent(m -> m.end(now, why));
        // Written before the new one goes in: at most one membership is active (uq_staff_membership_active),
        // and the new row's IDENTITY insert happens at once, ahead of the flush that would carry this update.
        memberships.flush();
        endCareTeams(employee, now);
        repository.leaveRooms(id);
        java.util.UUID from = employee.getClinic() == null ? null : employee.getClinic().getClinicId();
        employee.setClinic(target);
        memberships.save(new com.vn.vitalcare.entity.StaffMembership(employee, target, now));
        Employee saved = repository.persist(employee);
        events.publishEvent(new com.vn.vitalcare.share.audit.AuditEvent("employees", id, "transfer",
                java.util.Map.of("clinicId", String.valueOf(from)), java.util.Map.of("clinicId", target.getClinicId().toString())));
        // Their clinic is what their policies compare against: drop what is cached for the account.
        events.publishEvent(new AuthoritiesChanged.ForUser(employee.getUser().getId()));
        return saved;
    }

    @Override
    @Transactional
    public Employee endMembership(Long id, String reason) {
        Employee employee = lock(id, Action.WRITE);
        if (employee.getStatus() != EmployeeStatus.ACTIVE) {
            throw new ConflictException("Their membership has already ended");
        }
        changeStatus(employee, EmployeeStatus.INACTIVE, reason == null || reason.isBlank() ? "Kết thúc thành viên" : reason.trim());
        events.publishEvent(new com.vn.vitalcare.share.audit.AuditEvent("employees", id, "membership_end", null,
                java.util.Map.of("reason", reason == null ? "" : reason)));
        return repository.persist(employee);
    }

    @Override
    public List<String> clinicRoles(Long id) {
        return find(id, Action.READ).getUser().roleCodes().stream()
                .filter(com.vn.vitalcare.care.staff.service.StaffRoles.CLINIC_LEVEL::containsKey).sorted().toList();
    }

    @Override
    @Transactional
    public Employee setClinicRoles(Long id, java.util.Set<String> roles) {
        Employee employee = find(id, Action.WRITE);
        if (CurrentUser.id().filter(me -> me.equals(employee.getUser().getId())).isPresent()) {
            throw new ConflictException("Your own roles are changed by somebody else");
        }
        if (roles == null || roles.isEmpty()) {
            throw new com.vn.vitalcare.share.exception.FieldValidationException("roles", "Give them at least one role");
        }
        for (String code : roles) {
            if (!com.vn.vitalcare.care.staff.service.StaffRoles.CLINIC_LEVEL.containsKey(code)) {
                throw new com.vn.vitalcare.share.exception.FieldValidationException("roles", "Not a clinic-level role: " + code);
            }
        }
        // Only the system administrator makes a clinic administrator, or takes that away.
        boolean admin = CurrentUser.has(com.vn.vitalcare.share.security.Permissions.STAFF_MEMBERSHIPS_TRANSFER);
        java.util.Set<String> had = new java.util.HashSet<>(employee.getUser().roleCodes());
        // The other roles are the clinic's to give: whoever runs its staff (employees:write).
        boolean staffManager = CurrentUser.has(com.vn.vitalcare.share.security.Permissions.EMPLOYEES_WRITE);
        for (String code : com.vn.vitalcare.care.staff.service.StaffRoles.CLINIC_LEVEL.keySet()) {
            if (roles.contains(code) == had.contains(code)) {
                continue;
            }
            if (com.vn.vitalcare.care.staff.service.StaffRoles.ADMIN_ONLY.contains(code) ? !admin : !staffManager) {
                throw new org.springframework.security.authorization.AuthorizationDeniedException(
                        com.vn.vitalcare.care.staff.service.StaffRoles.ADMIN_ONLY.contains(code)
                                ? "Only the system administrator gives or takes away that role"
                                : "Only the clinic administrator gives or takes away that role");
            }
        }
        userService.replaceRolesWithin(employee.getUser().getId(),
                com.vn.vitalcare.care.staff.service.StaffRoles.CLINIC_LEVEL.keySet(), roles);
        return employee;
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
    @Override
    public List<Employee> doctorsOf(UUID clinicId) {
        return repository.findByClinicIdAndStaffTypeAndStatusAndDeletedAtIsNullOrderByIdAsc(
                clinicId, StaffType.DOCTOR, EmployeeStatus.ACTIVE);
    }

    @Override
    public Employee doctor(Long id) {
        return activeDoctor(repository.findByIdAndDeletedAtIsNull(id), id);
    }

    @Override
    @Transactional
    public Employee lockDoctor(Long id) {
        return activeDoctor(repository.findForUpdate(id), id);
    }

    private static Employee activeDoctor(Optional<Employee> found, Long id) {
        return found
                .filter(row -> row.getStaffType() == StaffType.DOCTOR && row.getStatus() == EmployeeStatus.ACTIVE)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor", id));
    }

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
