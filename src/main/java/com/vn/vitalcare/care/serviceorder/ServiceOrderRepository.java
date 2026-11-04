package com.vn.vitalcare.care.serviceorder;

import com.vn.vitalcare.entity.ServiceOrder;
import com.vn.vitalcare.share.data.SecuredRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Every read fetches what a response shows: the service, the patient and the encounter. */
public interface ServiceOrderRepository extends SecuredRepository<ServiceOrder, Long> {

    // No files on a page of rows: a collection fetch would page in memory.
    @Override
    @EntityGraph(attributePaths = {"service", "customer", "customer.user", "encounter"})
    Page<ServiceOrder> findAll(Specification<ServiceOrder> spec, Pageable pageable);

    @Override
    @EntityGraph(attributePaths = {"service", "customer", "customer.user", "encounter"})
    List<ServiceOrder> findAll(Specification<ServiceOrder> spec);

    @Override
    @EntityGraph(attributePaths = {"service", "customer", "customer.user", "encounter", "files"})
    Optional<ServiceOrder> findOne(Specification<ServiceOrder> spec);

    /**
     * One encounter's orders, oldest first. Not scoped: the caller has read
     * the encounter through its own scope (it is the encounter's page, its
     * completion, or its bill).
     */
    @Query("select distinct o from ServiceOrder o join fetch o.service join fetch o.customer c "
            + "left join fetch c.user left join fetch o.files "
            + "where o.encounter.id = :encounterId and o.deletedAt is null order by o.id")
    List<ServiceOrder> findByEncounter(@Param("encounterId") Long encounterId);
}
