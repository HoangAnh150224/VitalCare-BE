package com.vn.vitalcare.care.monitoring;

import com.vn.vitalcare.entity.MonitoringOrder;
import com.vn.vitalcare.share.data.SecuredRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MonitoringOrderRepository extends SecuredRepository<MonitoringOrder, Long> {

    @Override
    @EntityGraph(attributePaths = "customer")
    List<MonitoringOrder> findAll(Specification<MonitoringOrder> spec);

    @Override
    @EntityGraph(attributePaths = "customer")
    Optional<MonitoringOrder> findOne(Specification<MonitoringOrder> spec);

    /** Whether the patient has consented to monitoring that is still on (not scoped: the device desk's rule). */
    @Query("select count(o) > 0 from MonitoringOrder o where o.customer.id = :customerId and o.status = 'ACTIVE'"
            + " and o.deletedAt is null")
    boolean hasActive(@Param("customerId") Long customerId);
}
