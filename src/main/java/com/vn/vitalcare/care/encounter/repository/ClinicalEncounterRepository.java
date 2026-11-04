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
     * Locks the row and nothing else: taken before the scoped read, so that
     * read sees the row as the previous holder left it. Ordering a service and
     * concluding the visit both take it, so an order cannot slip in after the
     * visit was billed.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e.id from ClinicalEncounter e where e.id = :id")
    Optional<Long> lockRow(@Param("id") Long id);

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
