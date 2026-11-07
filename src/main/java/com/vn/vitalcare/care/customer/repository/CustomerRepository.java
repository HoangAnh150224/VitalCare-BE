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
    @EntityGraph(attributePaths = {"user", "guardian", "guardian.user"})
    Page<Customer> findAll(Specification<Customer> spec, Pageable pageable);

    @Override
    @EntityGraph(attributePaths = {"user", "guardian", "guardian.user"})
    List<Customer> findAll(Specification<Customer> spec);

    @EntityGraph(attributePaths = {"user", "guardian", "guardian.user"})
    Optional<Customer> findByIdAndDeletedAtIsNull(Long id);

    @EntityGraph(attributePaths = {"user", "guardian", "guardian.user"})
    Optional<Customer> findByUserIdAndDeletedAtIsNull(Long userId);

    /**
     * Profiles taken down at the desk with this number, before anybody has an
     * account for them (UC-04): the person registering picks their own, the
     * others join the account as relatives sharing the number.
     */
    @Query("select c from Customer c where c.deletedAt is null and c.user is null and c.guardian is null "
            + "and c.phone = :phone order by c.id")
    List<Customer> findUnclaimedByPhone(@Param("phone") String phone);

    /** Duplicate guard for a newly entered walk-in; phone alone never identifies a person. */
    @EntityGraph(attributePaths = {"user", "guardian", "guardian.user"})
    @Query("select c from Customer c left join c.user u where c.deletedAt is null and ("
            + "(:nationalId is not null and lower(trim(c.nationalId)) = lower(trim(:nationalId))) or ("
            + "(:nationalId is null or c.nationalId is null or trim(c.nationalId) = '') and "
            + "lower(trim(coalesce(c.fullName, u.fullName))) = lower(trim(:fullName)) "
            + "and c.dateOfBirth = :dateOfBirth "
            + "and coalesce(c.phone, u.phone) = :phone))")
    List<Customer> findWalkInDuplicates(@Param("phone") String phone,
                                        @Param("fullName") String fullName,
                                        @Param("dateOfBirth") java.time.LocalDate dateOfBirth,
                                        @Param("nationalId") String nationalId);

    /** The relatives an account's owner registers visits for, oldest first. */
    List<Customer> findByGuardianIdAndDeletedAtIsNullOrderByIdAsc(Long guardianId);

    Optional<Customer> findByIdAndGuardianIdAndDeletedAtIsNull(Long id, Long guardianId);

    /**
     * The customer, locked until the transaction ends — taken before
     * assigning a patient a carer or a device, so the "already on the team" /
     * "already wearing one" checks cannot be raced by a second desk or a
     * double click.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Customer c left join fetch c.user where c.id = :id and c.deletedAt is null")
    Optional<Customer> findForUpdate(@Param("id") Long id);
}
