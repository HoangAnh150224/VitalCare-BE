package com.vn.vitalcare.care.sharing;

import com.vn.vitalcare.entity.SharingPermission;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SharingPermissionRepository extends JpaRepository<SharingPermission, Long> {

    /** Every grant of these profiles, in force or not — the patient's Sharing Permission List and History. */
    @EntityGraph(attributePaths = {"customer", "caregiver"})
    @Query("select p from SharingPermission p where p.customer.id in :customerIds and p.deletedAt is null"
            + " order by p.createdAt desc")
    List<SharingPermission> findForProfiles(@Param("customerIds") Collection<Long> customerIds);

    /** The grants in force to a caregiver. */
    @EntityGraph(attributePaths = {"customer", "caregiver"})
    @Query("select p from SharingPermission p where p.caregiver.id = :userId and p.status = 'ACTIVE'"
            + " and p.deletedAt is null and (p.expiresAt is null or p.expiresAt > :now) order by p.createdAt")
    List<SharingPermission> findInForceFor(@Param("userId") Long userId, @Param("now") OffsetDateTime now);

    /** A patient's grants in force (BR: at most two). */
    @EntityGraph(attributePaths = {"customer", "caregiver"})
    @Query("select p from SharingPermission p where p.customer.id = :customerId and p.status = 'ACTIVE'"
            + " and p.deletedAt is null and (p.expiresAt is null or p.expiresAt > :now)")
    List<SharingPermission> findInForceOf(@Param("customerId") Long customerId, @Param("now") OffsetDateTime now);

    /** One grant with its patient and caregiver: what every answer about it shows. */
    @EntityGraph(attributePaths = {"customer", "caregiver"})
    @Query("select p from SharingPermission p where p.id = :id")
    java.util.Optional<SharingPermission> findWithParties(@Param("id") Long id);

    /** Serialises grants per patient, so two at once cannot both be the second. */
    @Query(value = "select pg_advisory_xact_lock(4206, cast(:key as integer))", nativeQuery = true)
    Object lockPatient(@Param("key") long key);
}
