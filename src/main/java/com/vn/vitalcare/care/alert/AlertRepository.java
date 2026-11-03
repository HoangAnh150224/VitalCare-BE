package com.vn.vitalcare.care.alert;

import com.vn.vitalcare.entity.Alert;
import com.vn.vitalcare.share.data.SecuredRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AlertRepository extends SecuredRepository<Alert, Long> {

    @Override
    @EntityGraph(attributePaths = {"customer", "device", "rule"})
    Page<Alert> findAll(Specification<Alert> spec, Pageable pageable);

    @Override
    @EntityGraph(attributePaths = {"customer", "device", "rule", "vitalSign"})
    Optional<Alert> findOne(Specification<Alert> spec);

    /** The patient's open alert on a measure, if any (not scoped: the engine). */
    @Query("select a from Alert a where a.customer.id = :customerId and a.metric = :metric"
            + " and a.status in ('OPEN', 'ACKNOWLEDGED') and a.deletedAt is null order by a.triggeredAt desc")
    List<Alert> findActive(@Param("customerId") Long customerId, @Param("metric") String metric);

    @EntityGraph(attributePaths = {"customer", "device", "rule"})
    @Query("select a from Alert a where a.customer.id in :customerIds and a.deletedAt is null order by a.triggeredAt desc")
    Page<Alert> findForProfiles(@Param("customerIds") List<Long> customerIds, Pageable pageable);

    /** One alert with its patient and whoever's account hears for them (not scoped: notifications). */
    @Query("select a from Alert a join fetch a.customer c left join fetch c.user left join fetch c.guardian g"
            + " left join fetch g.user where a.id = :id")
    Optional<Alert> findForNotice(@Param("id") Long id);

    /**
     * Serialises everything that changes a patient's alerts — the engine taking a reading in, staff handling
     * one — so two readings cannot open two alerts and a reading cannot land on an alert being closed.
     * Two-key form, with a namespace of its own, so it never meets another advisory lock.
     */
    @Query(value = "select pg_advisory_xact_lock(4205, cast(:key as integer))", nativeQuery = true)
    Object lockPatient(@Param("key") long key);

    /** Whose alert it is, unscoped — only to take that patient's lock before the scoped read. */
    @Query("select a.customer.id from Alert a where a.id = :id")
    Optional<Long> customerOf(@Param("id") Long id);
}
