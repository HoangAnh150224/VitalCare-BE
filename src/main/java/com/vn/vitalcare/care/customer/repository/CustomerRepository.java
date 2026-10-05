package com.vn.vitalcare.care.customer.repository;

import com.vn.vitalcare.entity.Customer;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

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
}
