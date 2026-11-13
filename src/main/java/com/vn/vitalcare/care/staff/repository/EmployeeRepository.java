package com.vn.vitalcare.care.staff.repository;

import com.vn.vitalcare.entity.Employee;
import com.vn.vitalcare.entity.EmployeeStatus;
import com.vn.vitalcare.entity.StaffType;
import com.vn.vitalcare.share.data.SecuredRepository;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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

    /** Visits today or later still to be seen by this doctor (a move to another clinic waits for them). */
    @Query(nativeQuery = true, value = "select count(*) from appointment where employee_id = :employeeId"
            + " and deleted_at is null and appointment_date >= :today"
            + " and status in ('BOOKED', 'CHECKED_IN', 'READY_FOR_EXAMINATION', 'IN_EXAM')")
    long countUpcomingVisits(@Param("employeeId") Long employeeId, @Param("today") java.time.LocalDate today);

    /** Their working sessions leave the old clinic's rooms; the new clinic places them in its own. */
    @org.springframework.data.jpa.repository.Modifying
    @Query(nativeQuery = true, value = "update doctor_working_hours set exam_room_id = null where employee_id = :employeeId")
    int leaveRooms(@Param("employeeId") Long employeeId);

    /**
     * The employee, locked until the transaction ends — taken both when they
     * are added to a care team and when they leave, so somebody being set
     * inactive cannot be added to a team in the same moment and keep an
     * assignment nothing will ever end.
     */
    // Not scoped here -- a locking query takes no specification. The service
    // checks the row-level scope on the locked row before using it.
    /**
     * Not scoped: a clinic's doctors are what anybody registering a visit
     * chooses from, a customer included, and a customer holds no staff scope.
     */
    @EntityGraph(attributePaths = "user")
    List<Employee> findByClinicIdAndStaffTypeAndStatusAndDeletedAtIsNullOrderByIdAsc(
            UUID clinicId, StaffType staffType, EmployeeStatus status);

    /** Not scoped, for the same reason: the one doctor a registration names. */
    @EntityGraph(attributePaths = "user")
    Optional<Employee> findByIdAndDeletedAtIsNull(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Employee e join fetch e.user where e.id = :id and e.deletedAt is null")
    Optional<Employee> findForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Clinic c where c.clinicId = :clinicId")
    Optional<com.vn.vitalcare.entity.Clinic> lockRoleClinic(@Param("clinicId") UUID clinicId);

    @Query("select count(distinct e.id) from Employee e join e.user u join u.roles r "
            + "where e.clinicId = :clinicId and e.id <> :excludedId and e.deletedAt is null "
            + "and e.status = com.vn.vitalcare.entity.EmployeeStatus.ACTIVE "
            + "and u.deletedAt is null and u.status = com.vn.vitalcare.identity.user.entity.UserStatus.ACTIVE "
            + "and r.code = 'CLINIC_ADMIN'")
    long countOtherActiveAdmins(@Param("clinicId") UUID clinicId, @Param("excludedId") Long excludedId);
}
