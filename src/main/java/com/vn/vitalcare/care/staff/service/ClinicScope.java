package com.vn.vitalcare.care.staff.service;

import java.util.Optional;
import java.util.UUID;

/**
 * The clinic whose data the signed-in person is limited to.
 *
 * <p>A member of staff works at one clinic, and the front-desk screens —
 * appointments, customers, devices, staff — answer them with that clinic's
 * rows only. Somebody with no staff record (an administrator) is not tied to a
 * clinic and sees every clinic's.
 *
 * <p>Services apply it before the caller's own filters, so nothing in a query
 * string can widen it; a row outside it answers as not found, the way somebody
 * else's appointment does for a customer.
 */
public interface ClinicScope {

    /** The signed-in person's clinic; empty when they are not limited to one. */
    Optional<UUID> currentClinicId();
}
