package com.vn.vitalcare.care.schedule.repository;

import com.vn.vitalcare.entity.DoctorSchedule;
import com.vn.vitalcare.share.data.SecuredRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;

/** Every read fetches the doctor, whom a response names. */
public interface DoctorScheduleRepository extends SecuredRepository<DoctorSchedule, Long> {

    @Override
    @EntityGraph(attributePaths = {"employee", "employee.user"})
    Page<DoctorSchedule> findAll(Specification<DoctorSchedule> spec, Pageable pageable);

    @Override
    @EntityGraph(attributePaths = {"employee", "employee.user"})
    List<DoctorSchedule> findAll(Specification<DoctorSchedule> spec);

    @Override
    @EntityGraph(attributePaths = {"employee", "employee.user"})
    Optional<DoctorSchedule> findOne(Specification<DoctorSchedule> spec);
}
