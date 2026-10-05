package com.vn.vitalcare.care.clinic.repository;

import com.vn.vitalcare.entity.Clinic;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ClinicRepository extends JpaRepository<Clinic, UUID> {

    Optional<Clinic> findByClinicIdAndDeletedAtIsNull(UUID clinicId);

    List<Clinic> findByDeletedAtIsNullOrderByClinicNameAsc();

    /**
     * The clinic, locked until the transaction ends — taken by every booking
     * before it counts a slot.
     *
     * <p>Counting and then inserting is otherwise a race: two people booking
     * the last place in a slot at once both count two of three, and both get
     * in. Locked, the second waits and counts three. One lock per clinic
     * rather than per slot keeps it simple; bookings at one clinic are not
     * frequent enough for the queue to show.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Clinic c where c.clinicId = :clinicId and c.deletedAt is null")
    Optional<Clinic> findForUpdate(@Param("clinicId") UUID clinicId);
}
