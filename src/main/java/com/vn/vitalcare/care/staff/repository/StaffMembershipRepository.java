package com.vn.vitalcare.care.staff.repository;

import com.vn.vitalcare.entity.StaffMembership;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Memberships; reached only through their member of staff, who is checked against the caller's scope first. */
public interface StaffMembershipRepository extends JpaRepository<StaffMembership, Long> {

    @EntityGraph(attributePaths = {"clinic"})
    @Query("select m from StaffMembership m where m.employee.id = :employeeId and m.deletedAt is null"
            + " order by m.startedAt desc, m.id desc")
    List<StaffMembership> findHistory(@Param("employeeId") Long employeeId);

    @Query("select m from StaffMembership m where m.employee.id = :employeeId and m.status = 'ACTIVE'"
            + " and m.deletedAt is null")
    Optional<StaffMembership> findActive(@Param("employeeId") Long employeeId);
}
