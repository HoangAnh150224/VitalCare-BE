package com.vn.vitalcare.care.results;

import com.vn.vitalcare.entity.PatientFeedback;
import com.vn.vitalcare.share.data.SecuredRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PatientFeedbackRepository extends SecuredRepository<PatientFeedback, Long> {

    @Override
    @EntityGraph(attributePaths = {"customer", "appointment", "appointment.employee", "appointment.employee.user"})
    Page<PatientFeedback> findAll(Specification<PatientFeedback> spec, Pageable pageable);

    @Override
    @EntityGraph(attributePaths = {"customer", "appointment", "appointment.employee", "appointment.employee.user"})
    Optional<PatientFeedback> findOne(Specification<PatientFeedback> spec);

    @EntityGraph(attributePaths = {"customer", "appointment", "appointment.employee", "appointment.employee.user"})
    @Query("select f from PatientFeedback f where f.customer.id in :ids and f.deletedAt is null order by f.createdAt desc")
    List<PatientFeedback> findForProfiles(@Param("ids") List<Long> ids);

    /** One with everything its answer shows: read after a write, for serialising outside the transaction. */
    @EntityGraph(attributePaths = {"customer", "appointment", "appointment.employee", "appointment.employee.user"})
    @Query("select f from PatientFeedback f where f.id = :id")
    Optional<PatientFeedback> findWithParties(@Param("id") Long id);

    @Query("select count(f) > 0 from PatientFeedback f where f.appointment.id = :appointmentId and f.deletedAt is null")
    boolean existsForVisit(@Param("appointmentId") Long appointmentId);
}
