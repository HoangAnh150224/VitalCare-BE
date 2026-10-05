package com.vn.vitalcare.care.appointment.repository;

import com.vn.vitalcare.entity.Appointment;
import com.vn.vitalcare.entity.AppointmentStatus;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Every read fetches what a response shows — the customer, their account and
 * the clinic — for the reason given on {@code CustomerRepository}.
 */
public interface AppointmentRepository extends JpaRepository<Appointment, Long>, JpaSpecificationExecutor<Appointment> {

    @Override
    @EntityGraph(attributePaths = {"customer", "customer.user", "clinic"})
    Page<Appointment> findAll(Specification<Appointment> spec, Pageable pageable);

    @Override
    @EntityGraph(attributePaths = {"customer", "customer.user", "clinic"})
    List<Appointment> findAll(Specification<Appointment> spec);

    @EntityGraph(attributePaths = {"customer", "customer.user", "clinic"})
    Optional<Appointment> findByIdAndDeletedAtIsNull(Long id);

    @EntityGraph(attributePaths = {"customer", "customer.user", "clinic"})
    Optional<Appointment> findByIdAndCustomerIdAndDeletedAtIsNull(Long id, Long customerId);

    /**
     * How many places are taken in each slot of a clinic's day, keyed by start
     * time. Checked-in appointments count — the person came, the place was
     * used; cancelled ones give their place back.
     */
    @Query("select a.startTime as startTime, count(a) as booked from Appointment a "
            + "where a.clinic.clinicId = :clinicId and a.appointmentDate = :date "
            + "and a.status in :statuses and a.deletedAt is null group by a.startTime")
    List<SlotCount> countBySlot(@Param("clinicId") UUID clinicId,
                                @Param("date") LocalDate date,
                                @Param("statuses") Collection<AppointmentStatus> statuses);

    /** One row of {@link #countBySlot}. */
    interface SlotCount {
        LocalTime getStartTime();

        long getBooked();
    }

    @EntityGraph(attributePaths = {"customer", "customer.user", "clinic"})
    Optional<Appointment> findByBookingCodeAndDeletedAtIsNull(String bookingCode);

    boolean existsByBookingCode(String bookingCode);

    /** The same customer already holding this slot, which a second booking would duplicate. */
    boolean existsByCustomerIdAndAppointmentDateAndStartTimeAndStatusAndDeletedAtIsNull(
            Long customerId, LocalDate appointmentDate, LocalTime startTime, AppointmentStatus status);
}
