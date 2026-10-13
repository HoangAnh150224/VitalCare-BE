package com.vn.vitalcare.care.customer.service;

import com.vn.vitalcare.care.customer.dto.CustomerPatchRequest;
import com.vn.vitalcare.care.customer.dto.ProfileRequest;
import com.vn.vitalcare.entity.Customer;
import com.vn.vitalcare.entity.CustomerStatus;
import com.vn.vitalcare.entity.PatientActivationSource;
import com.vn.vitalcare.identity.user.entity.User;
import com.vn.vitalcare.share.web.ListParams;
import java.util.List;
import org.springframework.data.domain.Page;

public interface CustomerService {

    // --- the account owner's side: their own profile and their relatives' -----

    /** The owner's own profile first, then the relatives they register visits for. */
    List<Customer> profilesOf(Customer owner);

    /** One of those profiles; anybody else's answers as not found. */
    Customer ownProfile(Customer owner, Long id);

    /** A relative with no account of their own, under the owner's. */
    Customer createDependent(Customer owner, ProfileRequest request);

    /** Fills in the owner's own profile, or corrects a relative's. */
    Customer updateOwnProfile(Customer owner, Long id, ProfileRequest request);

    // --- the clinic's side -----------------------------------------------------

    Page<Customer> list(ListParams params);

    List<Customer> getMany(List<Long> ids);

    Customer get(Long id);

    /**
     * Any customer, whatever clinic they have used: booking is how somebody
     * from another clinic becomes this clinic's customer, so the desk must be
     * able to book for them. Finding them in the first place takes their full
     * phone number or code — see {@code CustomerSpecifications.exactly}.
     */
    Customer getForBooking(Long id);

    /** The customer, locked for the rest of the caller's transaction. See {@code CustomerRepository.findForUpdate}. */
    Customer getForUpdate(Long id);

    /**
     * The customer record behind the signed-in account.
     *
     * <p>A conflict rather than a 404 when there is none: the caller asked for
     * nothing by id, so "not found" would describe the wrong thing. Staff
     * accounts reach this only by calling a customer-only endpoint.
     */
    Customer getForCurrentUser();

    /**
     * The customer record for a freshly self-registered account. Starts
     * {@link CustomerStatus#NEUTRAL}: registering is not becoming a patient.
     */
    Customer createNeutral(User user);

    Customer update(Long id, CustomerPatchRequest request);

    /**
     * Activates a patient profile by hand, as a member of staff.
     *
     * <p>Refused for somebody who is already a patient rather than silently
     * accepted: the activation record says who did it and how, and a second
     * activation would either overwrite that or pretend to have happened.
     */
    Customer activateManually(Long id);

    /**
     * Turns a neutral customer into a patient. The one place that transition
     * happens, whichever path leads to it — a check-in calls this too, inside
     * its own transaction, so the arrival and the activation stand or fall
     * together.
     *
     * @return whether anything changed; a patient stays as they are
     */
    boolean activateIfNeutral(Customer customer, PatientActivationSource source);
}
