package com.vn.vitalcare.care.customer.repository;

import com.vn.vitalcare.entity.Customer;
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

/**
 * Every read fetches the account with the customer.
 *
 * <p>{@code Customer.user} is lazy and {@code open-in-view} is off, so a
 * response built after the transaction closes would otherwise fail on the
 * first name it reads. The graph loads it in the same query instead of one
 * query per row.
 */
public interface CustomerRepository extends JpaRepository<Customer, Long>, JpaSpecificationExecutor<Customer> {

    @Override
    @EntityGraph(attributePaths = "user")
    Page<Customer> findAll(Specification<Customer> spec, Pageable pageable);

    @Override
    @EntityGraph(attributePaths = "user")
    List<Customer> findAll(Specification<Customer> spec);

    @EntityGraph(attributePaths = "user")
    Optional<Customer> findByIdAndDeletedAtIsNull(Long id);

    @EntityGraph(attributePaths = "user")
    Optional<Customer> findByUserIdAndDeletedAtIsNull(Long userId);

    /**
     * The customer, locked until the transaction ends — taken before
     * assigning a patient a carer or a device, so the "already on the team" /
     * "already wearing one" checks cannot be raced by a second desk or a
     * double click.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Customer c join fetch c.user where c.id = :id and c.deletedAt is null")
    Optional<Customer> findForUpdate(@Param("id") Long id);
}
