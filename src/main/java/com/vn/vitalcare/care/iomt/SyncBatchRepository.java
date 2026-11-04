package com.vn.vitalcare.care.iomt;

import com.vn.vitalcare.entity.SyncBatch;
import com.vn.vitalcare.share.data.SecuredRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;

public interface SyncBatchRepository extends SecuredRepository<SyncBatch, Long> {

    @Override
    @EntityGraph(attributePaths = {"device", "customer"})
    Page<SyncBatch> findAll(Specification<SyncBatch> spec, Pageable pageable);

    @Override
    @EntityGraph(attributePaths = {"device", "customer"})
    Optional<SyncBatch> findOne(Specification<SyncBatch> spec);

    @Override
    @EntityGraph(attributePaths = {"device", "customer"})
    List<SyncBatch> findAll(Specification<SyncBatch> spec);
}
