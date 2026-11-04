package com.vn.vitalcare.care.results;

import com.vn.vitalcare.entity.ResultReturn;
import com.vn.vitalcare.share.data.SecuredRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ResultReturnRepository extends SecuredRepository<ResultReturn, Long> {

    @Override
    @EntityGraph(attributePaths = {"customer", "appointment"})
    Page<ResultReturn> findAll(Specification<ResultReturn> spec, Pageable pageable);

    @Override
    @EntityGraph(attributePaths = {"customer", "appointment"})
    Optional<ResultReturn> findOne(Specification<ResultReturn> spec);

    @EntityGraph(attributePaths = {"customer", "appointment"})
    @Query("select r from ResultReturn r where r.customer.id in :ids and r.deletedAt is null order by r.scheduledAt desc")
    List<ResultReturn> findForProfiles(@Param("ids") List<Long> ids);

    @Query("select count(r) > 0 from ResultReturn r where r.appointment.id = :appointmentId"
            + " and r.status in ('SCHEDULED', 'MISSED') and r.deletedAt is null")
    boolean hasOpen(@Param("appointmentId") Long appointmentId);

    /** Scheduled for before the cutoff and still not come (not scoped: the sweep). */
    @EntityGraph(attributePaths = {"customer", "customer.user", "customer.guardian", "customer.guardian.user"})
    @Query("select r from ResultReturn r where r.status = 'SCHEDULED' and r.scheduledAt < :cutoff and r.deletedAt is null")
    List<ResultReturn> findOverdue(@Param("cutoff") OffsetDateTime cutoff);
}
