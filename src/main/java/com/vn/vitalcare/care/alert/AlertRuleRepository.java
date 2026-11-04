package com.vn.vitalcare.care.alert;

import com.vn.vitalcare.entity.AlertRule;
import com.vn.vitalcare.share.data.SecuredRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AlertRuleRepository extends SecuredRepository<AlertRule, Long> {

    @Override
    @EntityGraph(attributePaths = "customer")
    Page<AlertRule> findAll(Specification<AlertRule> spec, Pageable pageable);

    @Override
    @EntityGraph(attributePaths = "customer")
    Optional<AlertRule> findOne(Specification<AlertRule> spec);

    /**
     * The rules that may apply to a reading — not scoped, the engine runs for
     * nobody's request: the patient's own, and the clinic's for everybody.
     */
    @Query("select r from AlertRule r where r.enabled = true and r.deletedAt is null"
            + " and (r.customer.id = :customerId or (r.customer is null and r.clinicId = :clinicId))")
    List<AlertRule> findApplicable(@Param("customerId") Long customerId, @Param("clinicId") UUID clinicId);
}
