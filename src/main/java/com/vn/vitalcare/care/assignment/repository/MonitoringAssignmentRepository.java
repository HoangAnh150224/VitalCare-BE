package com.vn.vitalcare.care.assignment.repository;

import com.vn.vitalcare.entity.MonitoringAssignment;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Care-team rows. "Open" means not yet ended ({@code unassignedAt} null) —
 * the same condition the partial unique index in 016 holds to.
 */
public interface MonitoringAssignmentRepository
        extends JpaRepository<MonitoringAssignment, Long>, JpaSpecificationExecutor<MonitoringAssignment> {

    /** The assignment list: both people a row names, fetched with it. */
    @Override
    @EntityGraph(attributePaths = {"customer", "customer.user", "employee", "employee.user"})
    Page<MonitoringAssignment> findAll(Specification<MonitoringAssignment> spec, Pageable pageable);

    @Override
    @EntityGraph(attributePaths = {"customer", "customer.user", "employee", "employee.user"})
    Optional<MonitoringAssignment> findOne(Specification<MonitoringAssignment> spec);

    /** A patient's care team, current and past, newest first. */
    @EntityGraph(attributePaths = {"employee", "employee.user"})
    List<MonitoringAssignment> findByCustomerIdAndDeletedAtIsNullOrderByAssignedAtDesc(Long customerId);

    @EntityGraph(attributePaths = {"employee", "employee.user"})
    List<MonitoringAssignment> findByCustomerIdAndUnassignedAtIsNullAndDeletedAtIsNull(Long customerId);

    @EntityGraph(attributePaths = {"employee", "employee.user"})
    Optional<MonitoringAssignment> findByIdAndCustomerIdAndDeletedAtIsNull(Long id, Long customerId);

    boolean existsByCustomerIdAndEmployeeIdAndUnassignedAtIsNullAndDeletedAtIsNull(Long customerId, Long employeeId);

    /** Every open assignment, oldest first — the administrator's view of every caseload at once. */
    @EntityGraph(attributePaths = {"customer", "customer.user", "employee", "employee.user"})
    List<MonitoringAssignment> findByUnassignedAtIsNullAndDeletedAtIsNullOrderByAssignedAtAsc();

    /** The patients a member of staff is following now — "my patients". */
    @EntityGraph(attributePaths = {"customer", "customer.user"})
    List<MonitoringAssignment> findByEmployeeIdAndUnassignedAtIsNullAndDeletedAtIsNullOrderByAssignedAtDesc(
            Long employeeId);
}
