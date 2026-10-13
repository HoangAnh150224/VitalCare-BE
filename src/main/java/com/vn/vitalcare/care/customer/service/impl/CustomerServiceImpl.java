package com.vn.vitalcare.care.customer.service.impl;

import com.vn.vitalcare.care.customer.dto.CustomerPatchRequest;
import com.vn.vitalcare.care.customer.repository.CustomerRepository;
import com.vn.vitalcare.care.customer.repository.CustomerSpecifications;
import com.vn.vitalcare.care.customer.service.CustomerService;
import com.vn.vitalcare.care.staff.service.ClinicScope;
import com.vn.vitalcare.entity.Customer;
import com.vn.vitalcare.entity.CustomerStatus;
import com.vn.vitalcare.entity.Employee;
import com.vn.vitalcare.entity.PatientActivationSource;
import com.vn.vitalcare.identity.rowlevel.service.RowLevelSecurity;
import com.vn.vitalcare.identity.user.entity.User;
import com.vn.vitalcare.share.data.BaseEntitySpecifications;
import com.vn.vitalcare.share.exception.ConflictException;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import com.vn.vitalcare.share.security.CurrentUser;
import com.vn.vitalcare.share.security.rowlevel.Action;
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

/** The {@link CustomerService} the application runs on. */
@Service
@Transactional(readOnly = true)
public class CustomerServiceImpl implements CustomerService {

    /** Sort properties the list endpoint accepts; anything else is ignored. */
    private static final Set<String> SORTABLE = Set.of("id", "customerCode", "status", "createdAt");

    private static final Sort DEFAULT_SORT = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final CustomerRepository repository;
    private final ClinicScope clinicScope;
    // Customers are not under row-level management -- a customer belongs to a
    // clinic only through its appointments and care team, which a policy
    // cannot express -- but whether the caller is held to a clinic at all is
    // read from it. See scope().
    private final RowLevelSecurity rowLevel;
    private final Clock clock;

    public CustomerServiceImpl(CustomerRepository repository,
                               ClinicScope clinicScope,
                               RowLevelSecurity rowLevel,
                               Clock clock) {
        this.repository = repository;
        this.clinicScope = clinicScope;
        this.rowLevel = rowLevel;
        this.clock = clock;
    }

    @Override
    public Page<Customer> list(ListParams params) {
        // A receptionist sees their clinic's customers, plus whoever a full
        // phone number or customer code names exactly — somebody walking in
        // from another clinic.
        Specification<Customer> visible = params.search()
                .filter(term -> clinicScope.currentClinicId().isPresent())
                .map(term -> scope().or(CustomerSpecifications.exactly(term)))
                .orElseGet(this::scope);
        return repository.findAll(
                visible.and(CustomerSpecifications.from(params)),
                params.pageable(SORTABLE, DEFAULT_SORT));
    }

    @Override
    public List<Customer> getMany(List<Long> ids) {
        Specification<Customer> byIds = (root, query, cb) -> root.get("id").in(ids);
        return repository.findAll(Specification.allOf(scope(), BaseEntitySpecifications.notDeleted(), byIds));
    }

    @Override
    public Customer get(Long id) {
        Customer customer = repository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Customer", id));
        // Another clinic's customer answers as not found, never as forbidden.
        Optional<Specification<Customer>> scope = boundScope();
        if (scope.isPresent() && !repository.exists(Specification.allOf(
                (root, query, cb) -> cb.equal(root.get("id"), id), scope.get()))) {
            throw new ResourceNotFoundException("Customer", id);
        }
        return customer;
    }

    @Override
    public Customer getForBooking(Long id) {
        return repository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Customer", id));
    }

    @Override
    @Transactional
    public Customer getForUpdate(Long id) {
        get(id);
        return repository.findForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Customer", id));
    }

    @Override
    public Customer getForCurrentUser() {
        Long userId = CurrentUser.id().orElseThrow(() -> new ConflictException("Not signed in"));
        return repository.findByUserIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new ConflictException("This account has no customer profile"));
    }

    @Override
    @Transactional
    public Customer createNeutral(User user) {
        Customer customer = new Customer();
        customer.setUser(user);
        Customer saved = repository.save(customer);
        // Derived from the id, so it is unique without a lookup and stable for
        // the life of the record. Set after the insert because the id does not
        // exist before it; the change is flushed with the same transaction.
        saved.setCustomerCode("KH%06d".formatted(saved.getId()));
        return saved;
    }

    @Override
    @Transactional
    public Customer update(Long id, CustomerPatchRequest request) {
        Customer customer = get(id);

        if (request.dateOfBirth() != null) {
            customer.setDateOfBirth(request.dateOfBirth());
        }
        if (request.gender() != null) {
            customer.setGender(blankToNull(request.gender()));
        }
        if (request.address() != null) {
            customer.setAddress(blankToNull(request.address()));
        }
        if (request.emergencyContactName() != null) {
            customer.setEmergencyContactName(blankToNull(request.emergencyContactName()));
        }
        if (request.emergencyContactPhone() != null) {
            customer.setEmergencyContactPhone(blankToNull(request.emergencyContactPhone()));
        }
        return repository.save(customer);
    }

    @Override
    @Transactional
    public Customer activateManually(Long id) {
        Customer customer = get(id);
        if (customer.getStatus() == CustomerStatus.PATIENT) {
            throw new ConflictException("This customer is already a patient");
        }
        activate(customer, PatientActivationSource.MANUAL);
        return repository.save(customer);
    }

    @Override
    @Transactional
    public boolean activateIfNeutral(Customer customer, PatientActivationSource source) {
        if (customer.getStatus() != CustomerStatus.NEUTRAL) {
            return false;
        }
        activate(customer, source);
        return true;
    }

    private void activate(Customer customer, PatientActivationSource source) {
        customer.activateAsPatient(source, CurrentUser.id().orElse(null), OffsetDateTime.now(clock));
    }

    /** The caller's customers as a filter; see {@link #boundScope()}. */
    private Specification<Customer> scope() {
        return boundScope().orElse((root, query, cb) -> cb.conjunction());
    }

    /**
     * The caller's clinic as a filter — the customers it serves, plus everybody
     * who has not booked anywhere yet — or empty when the caller is not held to
     * a clinic.
     *
     * <p>Somebody with no clinic is unrestricted only if their staff scope
     * reaches every clinic's staff too: an administrator. A clinic-bound role
     * held by an account with no staff record sees no clinic's staff, and so
     * no clinic's customers either, rather than every clinic's.
     */
    private Optional<Specification<Customer>> boundScope() {
        // Asked of the staff policy first, so somebody it leaves unrestricted
        // -- an administrator who also has a staff record -- sees every
        // clinic's customers, as they see every clinic's appointments.
        if (rowLevel.hasUnrestrictedScope(Employee.class, Action.READ)) {
            return Optional.empty();
        }
        return Optional.of(clinicScope.currentClinicId()
                .map(CustomerSpecifications::visibleAtClinic)
                .orElse((root, query, cb) -> cb.disjunction()));
    }

    private static String blankToNull(String value) {
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
