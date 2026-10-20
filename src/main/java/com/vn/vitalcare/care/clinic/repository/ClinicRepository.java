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

// The three counts below are not scoped: figures about one clinic for the page
// only somebody with clinics:read opens; no row comes back.
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

    @Query("select count(e) from Employee e where e.clinicId = :clinicId and e.deletedAt is null "
            + "and e.status = com.vn.vitalcare.entity.EmployeeStatus.ACTIVE")
    long countActiveStaff(@Param("clinicId") UUID clinicId);

    @Query("select count(d) from MedicalDevice d where d.clinicId = :clinicId and d.deletedAt is null")
    long countDevices(@Param("clinicId") UUID clinicId);

    /** Patients who have registered a visit there; somebody only followed by its staff is not counted. */
    @Query("select count(distinct a.customer.id) from Appointment a where a.clinicId = :clinicId "
            + "and a.deletedAt is null and a.customer.status = com.vn.vitalcare.entity.CustomerStatus.PATIENT")
    long countPatients(@Param("clinicId") UUID clinicId);
}
