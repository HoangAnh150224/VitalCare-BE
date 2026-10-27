package com.vn.vitalcare.care.appointment.repository;

import com.vn.vitalcare.entity.Appointment;
import com.vn.vitalcare.entity.AppointmentStatus;
import com.vn.vitalcare.share.data.SecuredRepository;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
    @EntityGraph(attributePaths = {"customer", "customer.user", "clinic", "employee", "employee.user", "examRoom"})
    Page<Appointment> findAll(Specification<Appointment> spec, Pageable pageable);

    @Override
    @EntityGraph(attributePaths = {"customer", "customer.user", "clinic", "employee", "employee.user", "examRoom"})
    List<Appointment> findAll(Specification<Appointment> spec);

    /** What {@code byId} and the booking-code lookup read through, with what the response shows. */
    @Override
    @EntityGraph(attributePaths = {"customer", "customer.user", "clinic", "employee", "employee.user", "examRoom"})
    Optional<Appointment> findOne(Specification<Appointment> spec);

    /**
     * Not scoped: pinned to one account — its owner's appointments and those
     * of the relatives under it. A customer holds no row-level scope.
     */
    @EntityGraph(attributePaths = {"customer", "customer.user", "clinic", "employee", "employee.user", "examRoom"})
    @Query("select a from Appointment a join a.customer c left join c.guardian g "
            + "where a.id = :id and a.deletedAt is null and (c.id = :ownerId or g.id = :ownerId)")
    Optional<Appointment> findOwn(@Param("id") Long id, @Param("ownerId") Long ownerCustomerId);

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

    /** How many people the doctor still has to see that day: registered, checked in or being seen. */
    @Query("select count(a) from Appointment a where a.employee.id = :employeeId and a.appointmentDate = :date "
            + "and a.status in (com.vn.vitalcare.entity.AppointmentStatus.SCHEDULED, "
            + "com.vn.vitalcare.entity.AppointmentStatus.CHECKED_IN, "
            + "com.vn.vitalcare.entity.AppointmentStatus.IN_EXAM) and a.deletedAt is null")
    long countWaiting(@Param("employeeId") Long employeeId, @Param("date") LocalDate date);

    /** Visits still to come whose expected time touches a period -- what blocking that period would strand. */
    @Query("select count(a) from Appointment a where a.employee.id = :employeeId and a.appointmentDate = :date "
            + "and a.status in (com.vn.vitalcare.entity.AppointmentStatus.SCHEDULED, "
            + "com.vn.vitalcare.entity.AppointmentStatus.CHECKED_IN, "
            + "com.vn.vitalcare.entity.AppointmentStatus.IN_EXAM) "
            + "and a.startTime < :end and a.endTime > :start and a.deletedAt is null")
    long countExpectedBetween(@Param("employeeId") Long employeeId, @Param("date") LocalDate date,
                              @Param("start") LocalTime start, @Param("end") LocalTime end);

    /**
     * The next person a doctor calls in today, locked, skipping any row
     * another transaction holds: those never called first, then those passed
     * over in the order they were, each by queue number. Somebody passed over
     * after {@code recallAfter} is left out for now, so a room does not call
     * straight back the person who just did not answer. Two dispatchers
     * asking at once get two different people, or one gets nobody, never the
     * same person twice. Not scoped: the dispatcher's, for the doctor sitting
     * in the room it is filling.
     *
     * <p>{@code NO KEY UPDATE} rather than {@code UPDATE}: the weaker lock
     * does not conflict with the key-share lock a foreign key takes, so a row
     * merely referenced elsewhere at that moment (an encounter being written
     * against it) is not skipped. A row the desk is editing still is, for the
     * length of that edit; the next call or sweep picks it up.
     */
    @Query(nativeQuery = true, value = "select * from appointment "
            + "where employee_id = :employeeId and appointment_date = :date and status = 'CHECKED_IN' "
            + "and deleted_at is null and (skipped_at is null or skipped_at <= :recallAfter) "
            + "order by skipped_at nulls first, queue_number nulls last, checked_in_at "
            + "limit 1 for no key update skip locked")
    Optional<Appointment> lockNextInQueue(@Param("employeeId") Long employeeId, @Param("date") LocalDate date,
                                          @Param("recallAfter") OffsetDateTime recallAfter);

    /**
     * Everyone checked in at a clinic today and not yet called, in the order
     * they will be called, for the board. Not scoped: the board has already
     * checked the caller may see this clinic.
     */
    @Query("select a from Appointment a join fetch a.customer join fetch a.employee e join fetch e.user "
            + "where a.clinicId = :clinicId and a.appointmentDate = :date "
            + "and a.status = com.vn.vitalcare.entity.AppointmentStatus.CHECKED_IN and a.deletedAt is null "
            + "order by e.id, a.skippedAt asc nulls first, a.queueNumber asc nulls last, a.checkedInAt")
    List<Appointment> findWaitingAt(@Param("clinicId") UUID clinicId, @Param("date") LocalDate date);

    /**
     * The accounts with a visit at this clinic today that is waiting, being
     * seen or just over: whom a change in the clinic's queue concerns. The
     * account is the profile's own, or its guardian's for a relative. Not
     * scoped: it only names whom to nudge, and says nothing to anyone.
     */
    @Query(nativeQuery = true, value = "select distinct coalesce(c.user_id, g.user_id) from appointment a "
            + "join customer c on c.id = a.customer_id left join customer g on g.id = c.guardian_id "
            + "where a.clinic_id = :clinicId and a.appointment_date = :date and a.deleted_at is null "
            + "and a.status in ('CHECKED_IN', 'IN_EXAM', 'COMPLETED', 'NO_SHOW') "
            + "and coalesce(c.user_id, g.user_id) is not null")
    List<Long> findQueueUserIds(@Param("clinicId") UUID clinicId, @Param("date") LocalDate date);

    // Not scoped: a booking code is unique across every clinic.
    boolean existsByBookingCode(String bookingCode);

    /** The same customer already registered with this doctor that day, which a second registration would duplicate. */
    boolean existsByCustomerIdAndEmployeeIdAndAppointmentDateAndStatusAndDeletedAtIsNull(
            Long customerId, Long employeeId, LocalDate appointmentDate, AppointmentStatus status);
}
