package com.vn.vitalcare.care.staff.repository;

import com.vn.vitalcare.entity.Employee;
import com.vn.vitalcare.share.data.SecuredRepository;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Every read fetches the account, which holds the name and number a response shows. */
public interface EmployeeRepository extends SecuredRepository<Employee, Long> {

    @Override
    @EntityGraph(attributePaths = "user")
    Page<Employee> findAll(Specification<Employee> spec, Pageable pageable);

    @Override
    @EntityGraph(attributePaths = "user")
    List<Employee> findAll(Specification<Employee> spec);

    /** What {@code byId} reads through: one row, with its account, for the response. */
    @Override
    @EntityGraph(attributePaths = "user")
    Optional<Employee> findOne(Specification<Employee> spec);

    /**
     * Not scoped: the caller's own staff record ({@code getForCurrentUser}), or
     * the system placing an account at its clinic for the authority cache.
     */
    @EntityGraph(attributePaths = "user")
    Optional<Employee> findByUserIdAndDeletedAtIsNull(Long userId);

    /**
     * The employee, locked until the transaction ends — taken both when they
     * are added to a care team and when they leave, so somebody being set
     * inactive cannot be added to a team in the same moment and keep an
     * assignment nothing will ever end.
     */
    // Not scoped here -- a locking query takes no specification. The service
    // checks the row-level scope on the locked row before using it.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Employee e join fetch e.user where e.id = :id and e.deletedAt is null")
    Optional<Employee> findForUpdate(@Param("id") Long id);
}
