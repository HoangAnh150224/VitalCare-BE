package com.vn.vitalcare.care.customer.service;

import com.vn.vitalcare.care.customer.dto.CustomerPatchRequest;
import com.vn.vitalcare.care.customer.repository.CustomerRepository;
import com.vn.vitalcare.care.customer.repository.CustomerSpecifications;
import com.vn.vitalcare.entity.Customer;
import com.vn.vitalcare.entity.CustomerStatus;
import com.vn.vitalcare.entity.PatientActivationSource;
import com.vn.vitalcare.identity.user.entity.User;
import com.vn.vitalcare.share.data.BaseEntitySpecifications;
import com.vn.vitalcare.share.exception.ConflictException;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import com.vn.vitalcare.share.security.CurrentUser;
import com.vn.vitalcare.share.web.ListParams;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CustomerService {

    /** Sort properties the list endpoint accepts; anything else is ignored. */
    private static final Set<String> SORTABLE = Set.of("id", "customerCode", "status", "createdAt");

    private static final Sort DEFAULT_SORT = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final CustomerRepository repository;
    private final Clock clock;

    public CustomerService(CustomerRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public Page<Customer> list(ListParams params) {
        return repository.findAll(
                CustomerSpecifications.from(params),
                params.pageable(SORTABLE, DEFAULT_SORT));
    }

    public List<Customer> getMany(List<Long> ids) {
        Specification<Customer> byIds = (root, query, cb) -> root.get("id").in(ids);
        return repository.findAll(Specification.allOf(BaseEntitySpecifications.notDeleted(), byIds));
    }

    public Customer get(Long id) {
        return repository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Customer", id));
    }

    /**
     * The customer record behind the signed-in account.
     *
     * <p>A conflict rather than a 404 when there is none: the caller asked for
     * nothing by id, so "not found" would describe the wrong thing. Staff
     * accounts reach this only by calling a customer-only endpoint.
     */
    public Customer getForCurrentUser() {
        Long userId = CurrentUser.id().orElseThrow(() -> new ConflictException("Not signed in"));
        return repository.findByUserIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new ConflictException("This account has no customer profile"));
    }

    /**
     * The customer record for a freshly self-registered account. Starts
     * {@link CustomerStatus#NEUTRAL}: registering is not becoming a patient.
     */
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

    /**
     * Activates a patient profile by hand, as a member of staff.
     *
     * <p>Refused for somebody who is already a patient rather than silently
     * accepted: the activation record says who did it and how, and a second
     * activation would either overwrite that or pretend to have happened.
     */
    @Transactional
    public Customer activateManually(Long id) {
        Customer customer = get(id);
        if (customer.getStatus() == CustomerStatus.PATIENT) {
            throw new ConflictException("This customer is already a patient");
        }
        activate(customer, PatientActivationSource.MANUAL);
        return repository.save(customer);
    }

    /**
     * Turns a neutral customer into a patient. The one place that transition
     * happens, whichever path leads to it — a check-in calls this too, inside
     * its own transaction, so the arrival and the activation stand or fall
     * together.
     *
     * @return whether anything changed; a patient stays as they are
     */
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

    private static String blankToNull(String value) {
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
