package com.vn.vitalcare.care.customer.service.impl;

import com.vn.vitalcare.care.customer.dto.ActivityStatusRequest;
import com.vn.vitalcare.share.phone.PhoneNumbers;
import java.util.ArrayList;
import com.vn.vitalcare.share.exception.FieldValidationException;
import com.vn.vitalcare.care.customer.dto.ProfileRequest;
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
import com.vn.vitalcare.identity.user.service.UserService;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The {@link CustomerService} the application runs on. */
@Service
@Transactional(readOnly = true)
public class CustomerServiceImpl implements CustomerService {

    /** Sort properties the list endpoint accepts; anything else is ignored. */
    private static final Set<String> SORTABLE = Set.of("id", "customerCode", "status", "createdAt");

    private static final Sort DEFAULT_SORT = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    /** Relatives under one account; a family, not a register. */
    private static final int MAX_DEPENDENTS = 10;

    private final CustomerRepository repository;
    private final ClinicScope clinicScope;
    // Customers are not under row-level management -- a customer belongs to a
    // clinic only through its appointments and care team, which a policy
    // cannot express -- but whether the caller is held to a clinic at all is
    // read from it. See scope().
    private final RowLevelSecurity rowLevel;
    private final Clock clock;
    private final UserService userService;

    @Autowired
    public CustomerServiceImpl(CustomerRepository repository,
                               ClinicScope clinicScope,
                               RowLevelSecurity rowLevel,
                               Clock clock,
                               UserService userService) {
        this.repository = repository;
        this.clinicScope = clinicScope;
        this.rowLevel = rowLevel;
        this.clock = clock;
        this.userService = userService;
    }

    /** Compatibility constructor for existing service-level callers. */
    public CustomerServiceImpl(CustomerRepository repository,
                               ClinicScope clinicScope,
                               RowLevelSecurity rowLevel,
                               Clock clock) {
        this(repository, clinicScope, rowLevel, clock, null);
    }

    @Override
    public Page<Customer> list(ListParams params) {
        // A receptionist sees their clinic's customers, plus whoever a full
        // phone number, customer code or identity number names exactly — somebody walking in
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
    public List<Customer> profilesOf(Customer owner) {
        List<Customer> profiles = new ArrayList<>();
        profiles.add(owner);
        profiles.addAll(repository.findByGuardianIdAndDeletedAtIsNullOrderByIdAsc(owner.getId()));
        return profiles;
    }

    @Override
    public Customer ownProfile(Customer owner, Long id) {
        if (id.equals(owner.getId())) {
            return owner;
        }
        return repository.findByIdAndGuardianIdAndDeletedAtIsNull(id, owner.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Profile", id));
    }

    @Override
    @Transactional
    public Customer createDependent(Customer owner, ProfileRequest request) {
        List<Customer> existing = repository.findByGuardianIdAndDeletedAtIsNullOrderByIdAsc(owner.getId());
        if (existing.size() >= MAX_DEPENDENTS) {
            throw new ConflictException("An account holds at most %d relatives".formatted(MAX_DEPENDENTS));
        }
        Customer dependent = new Customer();
        dependent.setGuardian(owner);
        applyProfile(dependent, request);
        // The same person entered twice -- a double click on Save, usually.
        if (existing.stream().anyMatch(other -> other.getFullName().equalsIgnoreCase(dependent.getFullName())
                && request.dateOfBirth().equals(other.getDateOfBirth()))) {
            throw new ConflictException("This relative is already under your account");
        }
        Customer saved = repository.save(dependent);
        saved.setCustomerCode("KH%06d".formatted(saved.getId()));
        return saved;
    }

    @Override
    @Transactional
    public Customer updateOwnProfile(Customer owner, Long id, ProfileRequest request) {
        ownProfile(owner, id);
        // Read again inside this transaction, with its account: the caller's
        // copy is detached, and saving that would hand back a row whose name
        // cannot be read once the transaction has closed.
        Customer profile = repository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Profile", id));
        applyProfile(profile, request);
        return repository.save(profile);
    }

    /**
     * A relative needs a name, a number to reach them on and what they are to
     * the owner. The owner's own name and number are the account's and stay
     * out of the profile.
     */
    /** Nobody is older than this: a date of birth further back is a typing slip (the forms say the same). */
    static final int MAX_AGE_YEARS = 120;

    /** A date of birth no more than {@link #MAX_AGE_YEARS} years before today; null is left to the caller. */
    private void requireLikelyBirthDate(java.time.LocalDate dateOfBirth) {
        if (dateOfBirth != null && dateOfBirth.isBefore(java.time.LocalDate.now(clock).minusYears(MAX_AGE_YEARS))) {
            throw new FieldValidationException("dateOfBirth", "Date of birth is more than 120 years ago");
        }
    }

    private void applyProfile(Customer profile, ProfileRequest request) {
        requireLikelyBirthDate(request.dateOfBirth());
        if (profile.isDependent()) {
            String name = request.fullName() == null ? "" : request.fullName().trim();
            String phone = request.phone() == null ? "" : request.phone().trim();
            String relationship = request.relationship() == null ? "" : request.relationship().trim();
            if (name.isEmpty()) {
                throw new FieldValidationException("fullName", "A name is required");
            }
            // Stored as sign-in numbers are, so the desk finds a relative by
            // number however it was typed here.
            String normalized = PhoneNumbers.normalize(phone)
                    .orElseThrow(() -> new FieldValidationException("phone", "A valid phone number is required"));
            if (relationship.isEmpty()) {
                throw new FieldValidationException("relationship", "Say whose profile this is");
            }
            profile.setFullName(name);
            profile.setPhone(normalized);
            profile.setRelationship(relationship);
        }
        profile.setDateOfBirth(request.dateOfBirth());
        profile.setGender(request.gender());
        String idType = request.nationalIdType() == null || request.nationalIdType().isBlank()
                ? "cccd" : request.nationalIdType();
        // The number has to fit the paper chosen: twelve digits for a citizen card or personal number,
        // six to nine letters and digits for a passport.
        if (!com.vn.vitalcare.care.customer.NationalIds.matches(idType, request.nationalId())) {
            throw new FieldValidationException("nationalId", "The number does not match the chosen kind of paper");
        }
        profile.setNationalId(com.vn.vitalcare.care.customer.NationalIds.normalize(idType, request.nationalId()));
        profile.setNationalIdType(idType);
        if (request.province() != null) {
            profile.setProvince(blankToNull(request.province()));
        }
        if (request.ward() != null) {
            profile.setWard(blankToNull(request.ward()));
        }
        // As in update: null leaves a field alone, an empty string clears a text one.
        if (request.nationalIdIssuedOn() != null) {
            profile.setNationalIdIssuedOn(request.nationalIdIssuedOn());
        }
        if (request.ethnicity() != null) {
            profile.setEthnicity(blankToNull(request.ethnicity()));
        }
        if (request.nationality() != null) {
            profile.setNationality(blankToNull(request.nationality()));
        }
        if (request.occupation() != null) {
            profile.setOccupation(blankToNull(request.occupation()));
        }
        if (request.address() != null) {
            profile.setAddress(blankToNull(request.address()));
        }
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
    public Customer createWalkInProfile(String fullName, String phone, java.time.LocalDate dateOfBirth,
                                        String gender, String nationalId) {
        String name = fullName == null ? "" : fullName.trim();
        String normalizedPhone = PhoneNumbers.normalize(phone)
                .orElseThrow(() -> new FieldValidationException("phone", "A valid phone number is required"));
        String normalizedNationalId = blankToNull(nationalId);

        if (!repository.findWalkInDuplicates(normalizedPhone, name, dateOfBirth, normalizedNationalId).isEmpty()) {
            throw new ConflictException(
                    "A matching patient profile already exists. Search and select it instead of creating another one");
        }

        Customer customer = new Customer();
        customer.setFullName(name);
        customer.setPhone(normalizedPhone);
        customer.setNationalId(normalizedNationalId);
        requireLikelyBirthDate(dateOfBirth);
        customer.setDateOfBirth(dateOfBirth);
        customer.setGender(gender);
        Customer saved = repository.save(customer);
        saved.setCustomerCode("KH%06d".formatted(saved.getId()));
        return saved;
    }

    @Override
    @Transactional
    public List<Customer> unclaimedWithPhone(String phone) {
        return repository.findUnclaimedByPhone(phone);
    }

    @Override
    @Transactional
    public Customer registerAccountProfile(User user, Long claimProfileId, java.time.LocalDate dateOfBirth) {
        // The account's number is proven by the code just spent: whoever holds it
        // may take any of these, which is why the list was only shown after the code.
        List<Customer> matches = repository.findUnclaimedByPhone(user.getPhone());

        Customer profile;
        if (claimProfileId != null) {
            profile = matches.stream().filter(match -> match.getId().equals(claimProfileId)).findFirst()
                    .orElseThrow(() -> new FieldValidationException("claimProfileId", "Pick one of the profiles shown"));
            // The owner's name and number are the account's from now on (see Customer).
            profile.setUser(user);
            profile.setFullName(null);
            profile.setPhone(null);
        } else {
            profile = new Customer();
            profile.setUser(user);
            requireLikelyBirthDate(dateOfBirth);
            profile.setDateOfBirth(dateOfBirth);
            profile = repository.save(profile);
            profile.setCustomerCode("KH%06d".formatted(profile.getId()));
        }

        // Having found themselves, the others with the number are relatives who share it (UC-04
        // step 11b); beyond what an account holds they stay unclaimed, for the desk as before.
        // "Not me" takes none of them: somebody none of these is gets nobody's history.
        if (claimProfileId == null) {
            if (userService != null) {
                userService.synchronizeCustomerRole(user, false);
            }
            return repository.save(profile);
        }
        Customer owner = profile;
        matches.stream()
                .filter(match -> !match.getId().equals(owner.getId()))
                .limit(MAX_DEPENDENTS)
                .forEach(relative -> {
                    relative.setGuardian(owner);
                    relative.setRelationship("other");
                    repository.save(relative);
                });

        if (userService != null) {
            userService.synchronizeCustomerRole(user, profile.getStatus() == CustomerStatus.PATIENT);
        }
        return repository.save(profile);
    }

    @Override
    @Transactional
    public Customer update(Long id, CustomerPatchRequest request) {
        Customer customer = get(id);

        if (request.dateOfBirth() != null) {
            requireLikelyBirthDate(request.dateOfBirth());
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
    public Customer changeActivityStatus(Long id, ActivityStatusRequest request) {
        Customer customer = get(id);
        String reason = request.reason() == null ? null : blankToNull(request.reason());
        if (!"active".equals(request.status()) && reason == null) {
            throw new FieldValidationException("reason", "Say why this person is no longer active");
        }
        if (request.status().equals(customer.getActivityStatus())) {
            throw new ConflictException("That is already the current status");
        }
        customer.changeActivity(request.status(), reason, CurrentUser.id().orElse(null), OffsetDateTime.now(clock));
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
        if (customer.getUser() != null && userService != null) {
            userService.synchronizeCustomerRole(customer.getUser(), true);
        }
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
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
