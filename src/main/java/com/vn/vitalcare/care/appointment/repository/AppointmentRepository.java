package com.vn.vitalcare.care.appointment.repository;

import com.vn.vitalcare.entity.Appointment;
import com.vn.vitalcare.entity.AppointmentStatus;
import com.vn.vitalcare.share.data.SecuredRepository;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Every read fetches what a response shows — the customer, their account, the
 * clinic and the doctor — for the reason given on {@code CustomerRepository}.
 */
public interface AppointmentRepository extends SecuredRepository<Appointment, Long> {

    @Override
    @EntityGraph(attributePaths = {"customer", "customer.user", "clinic", "employee", "employee.user"})
    Page<Appointment> findAll(Specification<Appointment> spec, Pageable pageable);

    @Override
    @EntityGraph(attributePaths = {"customer", "customer.user", "clinic", "employee", "employee.user"})
    List<Appointment> findAll(Specification<Appointment> spec);

    /** What {@code byId} and the booking-code lookup read through, with what the response shows. */
    @Override
    @EntityGraph(attributePaths = {"customer", "customer.user", "clinic", "employee", "employee.user"})
    Optional<Appointment> findOne(Specification<Appointment> spec);

    /** Not scoped: pinned to the customer, for their own appointments; they hold no row-level scope. */
    @EntityGraph(attributePaths = {"customer", "customer.user", "clinic", "employee", "employee.user"})
    Optional<Appointment> findByIdAndCustomerIdAndDeletedAtIsNull(Long id, Long customerId);

    // The three below are not scoped: they count one doctor's day to issue a
    // queue number or size the day, for whoever is registering -- a customer
    // holds no scope -- and return no row to anybody.

    /**
     * The highest queue number the doctor's day has issued, 0 when none.
     * Cancelled visits count: a number that was given out is not given again.
     * Called under the doctor's lock.
     */
    @Query("select coalesce(max(a.queueNumber), 0) from Appointment a "
            + "where a.employee.id = :employeeId and a.appointmentDate = :date and a.deletedAt is null")
    int maxQueueNumber(@Param("employeeId") Long employeeId, @Param("date") LocalDate date);

    /** How many people the doctor still has to see that day: registered or checked in, not yet seen. */
    @Query("select count(a) from Appointment a where a.employee.id = :employeeId and a.appointmentDate = :date "
            + "and a.status in (com.vn.vitalcare.entity.AppointmentStatus.SCHEDULED, "
            + "com.vn.vitalcare.entity.AppointmentStatus.CHECKED_IN) and a.deletedAt is null")
    long countWaiting(@Param("employeeId") Long employeeId, @Param("date") LocalDate date);

    /** Visits still to come whose expected time touches a period -- what blocking that period would strand. */
    @Query("select count(a) from Appointment a where a.employee.id = :employeeId and a.appointmentDate = :date "
            + "and a.status in (com.vn.vitalcare.entity.AppointmentStatus.SCHEDULED, "
            + "com.vn.vitalcare.entity.AppointmentStatus.CHECKED_IN) "
            + "and a.startTime < :end and a.endTime > :start and a.deletedAt is null")
    long countExpectedBetween(@Param("employeeId") Long employeeId, @Param("date") LocalDate date,
                              @Param("start") LocalTime start, @Param("end") LocalTime end);

    // Not scoped: a booking code is unique across every clinic.
    boolean existsByBookingCode(String bookingCode);

    /** The same customer already registered with this doctor that day, which a second registration would duplicate. */
    boolean existsByCustomerIdAndEmployeeIdAndAppointmentDateAndStatusAndDeletedAtIsNull(
            Long customerId, Long employeeId, LocalDate appointmentDate, AppointmentStatus status);
}
