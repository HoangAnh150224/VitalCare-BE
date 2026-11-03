package com.vn.vitalcare.care.sharing;

import com.vn.vitalcare.entity.EmergencyNotice;
import com.vn.vitalcare.entity.EscalationCall;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Emergency notices to caregivers (BF-06). */
public interface EmergencyNoticeRepository extends JpaRepository<EmergencyNotice, Long> {

    @EntityGraph(attributePaths = {"alert", "customer", "recipient", "permission"})
    @Query("select n from EmergencyNotice n where n.recipient.id = :userId order by n.notifiedAt desc")
    List<EmergencyNotice> findForRecipient(@Param("userId") Long userId, Pageable page);

    @EntityGraph(attributePaths = {"alert", "customer", "recipient", "permission"})
    @Query("select n from EmergencyNotice n where n.id = :id")
    Optional<EmergencyNotice> findWithDetails(@Param("id") Long id);

    /** Due: still pending past the delay. Locked and skipped if another sweep holds them. */
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.QueryHints(@jakarta.persistence.QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @EntityGraph(attributePaths = {"alert", "customer", "recipient"})
    @Query("select n from EmergencyNotice n where n.status = 'PENDING' and n.notifiedAt <= :cutoff order by n.notifiedAt")
    List<EmergencyNotice> findDue(@Param("cutoff") OffsetDateTime cutoff, Pageable page);

    boolean existsByAlertIdAndRecipientId(Long alertId, Long recipientId);
}
