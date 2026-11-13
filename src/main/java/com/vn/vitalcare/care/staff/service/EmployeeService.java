package com.vn.vitalcare.care.staff.service;

import com.vn.vitalcare.care.staff.dto.EmployeeCreateRequest;
import com.vn.vitalcare.care.staff.dto.EmployeePatchRequest;
import com.vn.vitalcare.entity.Employee;
import com.vn.vitalcare.share.web.ListParams;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;

/**
 * Clinical staff: the account they sign in with, their professional record,
 * and the moment they stop working here.
 */
public interface EmployeeService {

    Page<Employee> list(ListParams params);

    List<Employee> getMany(List<Long> ids);

    Employee get(Long id);

    /**
     * The staff record behind the signed-in account. A conflict rather than a
     * 404 when there is none, as {@code CustomerService.getForCurrentUser}.
     */
    Employee getForCurrentUser();

    /** SRS 3.18.9: the member of staff's memberships, newest first. */
    List<com.vn.vitalcare.entity.StaffMembership> memberships(Long id);

    /** Moves somebody to another clinic: their membership here ends, one there starts (the system administrator's). */
    Employee transfer(Long id, java.util.UUID clinicId, String reason);

    /** Ends their membership of the clinic: they leave, as setting them inactive does. */
    Employee endMembership(Long id, String reason);

    /** SRS 3.18.10: the roles they hold at the clinic, replacing the clinic-level ones they had. */
    Employee setClinicRoles(Long id, java.util.Set<String> roles);

    /** The clinic-level roles they hold now, read inside the transaction (the roles are loaded lazily). */
    List<String> clinicRoles(Long id);

    /**
     * A new member of staff: their account, holding the role their kind of
     * staff gets, and their record — one transaction, so there is never an
     * account without its record or the other way round.
     */
    Employee create(EmployeeCreateRequest request);

    /** The employee, locked for the rest of the caller's transaction. See {@code EmployeeRepository.findForUpdate}. */
    Employee lockForUpdate(Long id);

    Employee update(Long id, EmployeePatchRequest request);
    Employee deleteClinicAdministrator(Long id);

    /**
     * A clinic's active doctors — who a visit can be registered with.
     *
     * <p>Outside the "employees" row-level scope on purpose, like the two
     * below: a customer registering a visit holds no staff scope, and that a
     * clinic has these doctors is what the clinic tells everybody.
     */
    List<Employee> doctorsOf(UUID clinicId);

    /** One active doctor; anybody else, or nobody, answers as not found. */
    Employee doctor(Long id);

    /** {@link #doctor}, locked for the rest of the transaction — taken while a queue number is issued. */
    Employee lockDoctor(Long id);
}
