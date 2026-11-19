package com.vn.vitalcare.care.encounter.repository;

import com.vn.vitalcare.entity.ClinicalEncounter;
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

/** Every read fetches the patient and the clinician, which a response names. */
public interface ClinicalEncounterRepository extends SecuredRepository<ClinicalEncounter, Long> {

    /**
     * The patient, when this member of staff has examined them at least once:
     * their record is open to that doctor though nobody assigned them to follow
     * the patient. Not scoped: the caller checks it is their own staff id.
     */
    @Query("select e.customer from ClinicalEncounter e where e.customer.id = :customerId "
            + "and e.employee.id = :employeeId and e.deletedAt is null")
    java.util.List<com.vn.vitalcare.entity.Customer> examinedPatient(@Param("customerId") Long customerId,
                                                                     @Param("employeeId") Long employeeId,
                                                                     org.springframework.data.domain.Pageable first);

    /**
     * Locks the row and nothing else: taken before the scoped read, so that
     * read sees the row as the previous holder left it. Ordering a service and
     * concluding the visit both take it, so an order cannot slip in after the
     * visit was billed.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e.id from ClinicalEncounter e where e.id = :id")
    Optional<Long> lockRow(@Param("id") Long id);

    /**
     * Locks the visit's row, so two "Bắt đầu khám" for one visit are taken one
     * after the other. Unscoped: the caller has already proved the visit is its
     * patient's, at its clinic.
     */
    @Query(value = "select id from appointment where id = :appointmentId for update", nativeQuery = true)
    Optional<Long> lockAppointment(@Param("appointmentId") Long appointmentId);

    /** Whether the visit already has a live encounter. Unscoped, as lockAppointment. */
    @Query("select count(e) > 0 from ClinicalEncounter e where e.appointment.id = :appointmentId and e.deletedAt is null")
    boolean visitHasEncounter(@Param("appointmentId") Long appointmentId);

    /**
     * The encounters of one appointment, newest first — unscoped. Only for a
     * caller that has already proved the appointment is the signed-in
     * customer's own (MyVisitResults); never reachable from a staff path.
     */
    @Query("select e from ClinicalEncounter e join fetch e.customer c left join fetch c.user "
            + "left join fetch e.employee emp left join fetch emp.user "
            + "where e.appointment.id = :appointmentId and e.deletedAt is null order by e.id desc")
    List<ClinicalEncounter> findOfOwnAppointment(@Param("appointmentId") Long appointmentId);

    @Override
    @EntityGraph(attributePaths = {"customer", "customer.user", "employee", "employee.user"})
    Page<ClinicalEncounter> findAll(Specification<ClinicalEncounter> spec, Pageable pageable);

    @Override
    @EntityGraph(attributePaths = {"customer", "customer.user", "employee", "employee.user"})
    List<ClinicalEncounter> findAll(Specification<ClinicalEncounter> spec);

    @Override
    @EntityGraph(attributePaths = {"customer", "customer.user", "employee", "employee.user"})
    Optional<ClinicalEncounter> findOne(Specification<ClinicalEncounter> spec);
}
