package com.vn.vitalcare.care.encounter.repository;

import com.vn.vitalcare.entity.ClinicalEncounter;
import com.vn.vitalcare.share.data.SecuredRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;

/** Every read fetches the patient and the clinician, which a response names. */
public interface ClinicalEncounterRepository extends SecuredRepository<ClinicalEncounter, Long> {

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
