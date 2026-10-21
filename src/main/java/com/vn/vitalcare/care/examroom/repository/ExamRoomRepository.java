package com.vn.vitalcare.care.examroom.repository;

import com.vn.vitalcare.entity.ExamRoom;
import com.vn.vitalcare.share.data.SecuredRepository;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** A clinic's rooms. Reads for people go through a scope; the unscoped ones below are the dispatcher's. */
public interface ExamRoomRepository extends SecuredRepository<ExamRoom, Long> {

    @Override
    @EntityGraph(attributePaths = {"clinic"})
    Page<ExamRoom> findAll(Specification<ExamRoom> spec, Pageable pageable);

    @Override
    @EntityGraph(attributePaths = {"clinic"})
    List<ExamRoom> findAll(Specification<ExamRoom> spec);

    @Override
    @EntityGraph(attributePaths = {"clinic"})
    Optional<ExamRoom> findOne(Specification<ExamRoom> spec);

    /**
     * The room, locked until the transaction ends: what every change to who is
     * in it takes first, so two of them on one room run one after the other.
     * Not scoped: the caller has already read the room through a scope, or is
     * the dispatcher.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ExamRoom r where r.id = :id and r.deletedAt is null")
    Optional<ExamRoom> findForUpdate(@Param("id") Long id);

    /** A live room by id, unscoped: for a working session naming its room. */
    @Query("select r from ExamRoom r where r.id = :id and r.deletedAt is null")
    Optional<ExamRoom> findLive(@Param("id") Long id);

    /** Free rooms everywhere, for the sweep that catches a doctor's session starting. */
    @Query("select r.id from ExamRoom r where r.status = com.vn.vitalcare.entity.ExamRoomStatus.AVAILABLE "
            + "and r.deletedAt is null order by r.id")
    List<Long> findAvailableIds();

    /** Rooms still holding a visit from an earlier day: nobody finished it before closing. */
    @Query("select r.id from ExamRoom r join r.currentAppointment a "
            + "where a.appointmentDate < :today and r.deletedAt is null order by r.id")
    List<Long> findIdsHoldingVisitBefore(@Param("today") LocalDate today);

    /** Another live room of the clinic already using this code, ignoring case. */
    @Query("select count(r) > 0 from ExamRoom r where r.clinicId = :clinicId and lower(r.code) = lower(:code) "
            + "and r.deletedAt is null and (:excludeId is null or r.id <> :excludeId)")
    boolean codeTaken(@Param("clinicId") UUID clinicId, @Param("code") String code,
                      @Param("excludeId") Long excludeId);
}
