package com.vn.vitalcare.care.results;

import com.vn.vitalcare.entity.ProfessionalLicense;
import com.vn.vitalcare.share.data.SecuredRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProfessionalLicenseRepository extends SecuredRepository<ProfessionalLicense, Long> {

    @Override
    @EntityGraph(attributePaths = {"employee", "employee.user"})
    List<ProfessionalLicense> findAll(Specification<ProfessionalLicense> spec);

    @Override
    @EntityGraph(attributePaths = {"employee", "employee.user"})
    Optional<ProfessionalLicense> findOne(Specification<ProfessionalLicense> spec);

    /** The holder's own (not scoped: theirs by who they are). */
    @EntityGraph(attributePaths = {"employee", "employee.user"})
    @Query("select l from ProfessionalLicense l where l.employee.id = :employeeId and l.deletedAt is null"
            + " order by l.expiresOn nulls last")
    List<ProfessionalLicense> findOwn(@Param("employeeId") Long employeeId);
}
