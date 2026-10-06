package com.vn.vitalcare.care.staff.repository;

import com.vn.vitalcare.entity.Employee;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Every read fetches the account, which holds the name and number a response shows. */
public interface EmployeeRepository extends JpaRepository<Employee, Long>, JpaSpecificationExecutor<Employee> {

    @Override
    @EntityGraph(attributePaths = "user")
    Page<Employee> findAll(Specification<Employee> spec, Pageable pageable);

    @Override
    @EntityGraph(attributePaths = "user")
    List<Employee> findAll(Specification<Employee> spec);

    @EntityGraph(attributePaths = "user")
    Optional<Employee> findByIdAndDeletedAtIsNull(Long id);

    @EntityGraph(attributePaths = "user")
    Optional<Employee> findByUserIdAndDeletedAtIsNull(Long userId);

    /**
     * The employee, locked until the transaction ends — taken both when they
     * are added to a care team and when they leave, so somebody being set
     * inactive cannot be added to a team in the same moment and keep an
     * assignment nothing will ever end.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Employee e join fetch e.user where e.id = :id and e.deletedAt is null")
    Optional<Employee> findForUpdate(@Param("id") Long id);
}
