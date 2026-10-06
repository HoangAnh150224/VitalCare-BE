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
import com.vn.vitalcare.identity.user.entity.User;
import com.vn.vitalcare.identity.user.service.UserService;
import com.vn.vitalcare.share.data.BaseEntitySpecifications;
import com.vn.vitalcare.share.exception.ConflictException;
import com.vn.vitalcare.share.exception.FieldValidationException;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import com.vn.vitalcare.share.security.CurrentUser;
import com.vn.vitalcare.share.web.ListParams;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
    private final ClinicScope clinicScope;
    private final Clock clock;

    public EmployeeServiceImpl(EmployeeRepository repository,
                               MonitoringAssignmentRepository careTeams,
                               UserService userService,
                               ClinicService clinicService,
                               ClinicScope clinicScope,
                               Clock clock) {
        this.repository = repository;
        this.careTeams = careTeams;
        this.userService = userService;
        this.clinicService = clinicService;
        this.clinicScope = clinicScope;
        this.clock = clock;
    }

    @Override
    public Page<Employee> list(ListParams params) {
        return repository.findAll(scope().and(EmployeeSpecifications.from(params)),
                params.pageable(SORTABLE, DEFAULT_SORT));
    }

    @Override
    public List<Employee> getMany(List<Long> ids) {
        Specification<Employee> byIds = (root, query, cb) -> root.get("id").in(ids);
        return repository.findAll(Specification.allOf(scope(), BaseEntitySpecifications.notDeleted(), byIds));
    }

    @Override
    public Employee get(Long id) {
        return repository.findByIdAndDeletedAtIsNull(id)
                .filter(this::inScope)
                .orElseThrow(() -> new ResourceNotFoundException("Employee", id));
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
        User user = userService.createStaffAccount(
                request.phone(), request.password(), request.fullName(), request.staffType().roleCode());

        Employee employee = new Employee();
        employee.setUser(user);
        employee.setStaffType(request.staffType());
        employee.setSpecialty(blankToNull(request.specialty()));
        employee.setProfessionalTitle(blankToNull(request.professionalTitle()));
        employee.setLicenseNo(blankToNull(request.licenseNo()));
        employee.setClinicPosition(blankToNull(request.clinicPosition()));
        employee.setClinic(clinicFor(request.clinicId()));

        Employee saved = repository.save(employee);
        // Derived from the id, like the customer code.
        saved.setEmployeeCode("NV%06d".formatted(saved.getId()));
        return saved;
    }

    @Override
    @Transactional
    public Employee lockForUpdate(Long id) {
        return repository.findForUpdate(id)
                .filter(this::inScope)
                .orElseThrow(() -> new ResourceNotFoundException("Employee", id));
    }

    @Override
    @Transactional
    public Employee update(Long id, EmployeePatchRequest request) {
        // A status change takes the lock a care-team assignment takes, so
        // leaving and being assigned cannot interleave.
        Employee employee = request.status() == null ? get(id) : lockForUpdate(id);

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
        return repository.save(employee);
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
     * creator's own, else the first one. Somebody tied to a clinic cannot put
     * staff in another.
     */
    private Clinic clinicFor(UUID requested) {
        Optional<UUID> own = clinicScope.currentClinicId();
        if (requested != null) {
            if (own.isPresent() && !own.get().equals(requested)) {
                throw new FieldValidationException("clinicId", "You can only add staff to the clinic you work at");
            }
            return clinicService.get(requested);
        }
        return own.map(clinicService::get)
                .or(() -> clinicService.listAll().stream().findFirst())
                .orElse(null);
    }

    /** The caller's clinic as a filter; unrestricted for somebody tied to no clinic. */
    private Specification<Employee> scope() {
        return clinicScope.currentClinicId()
                .map(EmployeeSpecifications::atClinic)
                .orElse((root, query, cb) -> cb.conjunction());
    }

    /** Another clinic's member of staff answers as not found, never as forbidden. */
    private boolean inScope(Employee employee) {
        return clinicScope.currentClinicId()
                .map(clinic -> employee.getClinic() != null && clinic.equals(employee.getClinic().getClinicId()))
                .orElse(true);
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
