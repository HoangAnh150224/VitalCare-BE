package com.vn.vitalcare.care.staff.service;

import com.vn.vitalcare.care.staff.dto.EmployeeCreateRequest;
import com.vn.vitalcare.care.staff.dto.EmployeePatchRequest;
import com.vn.vitalcare.entity.Employee;
import com.vn.vitalcare.share.web.ListParams;
import java.util.List;
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

    /**
     * A new member of staff: their account, holding the role their kind of
     * staff gets, and their record — one transaction, so there is never an
     * account without its record or the other way round.
     */
    Employee create(EmployeeCreateRequest request);

    /** The employee, locked for the rest of the caller's transaction. See {@code EmployeeRepository.findForUpdate}. */
    Employee lockForUpdate(Long id);

    Employee update(Long id, EmployeePatchRequest request);
}
