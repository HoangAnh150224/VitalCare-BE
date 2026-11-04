package com.vn.vitalcare.care.iomt;

import com.vn.vitalcare.entity.SyncFailure;
import com.vn.vitalcare.share.data.SecuredRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;

public interface SyncFailureRepository extends SecuredRepository<SyncFailure, Long> {

    @Override
    @EntityGraph(attributePaths = {"device", "batch"})
    Page<SyncFailure> findAll(Specification<SyncFailure> spec, Pageable pageable);

    @Override
    @EntityGraph(attributePaths = {"device", "batch"})
    Optional<SyncFailure> findOne(Specification<SyncFailure> spec);

    @Override
    @EntityGraph(attributePaths = {"device", "batch"})
    List<SyncFailure> findAll(Specification<SyncFailure> spec);
}
