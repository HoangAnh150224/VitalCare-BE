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

/** The Caregiver Escalation and Call Log (BF-06). */
public interface EscalationCallRepository extends JpaRepository<EscalationCall, Long> {

    @Query("select c from EscalationCall c where c.alert.id = :alertId order by c.placedAt, c.id")
    List<EscalationCall> findForAlert(@Param("alertId") Long alertId);

    @Query("select c from EscalationCall c where c.notice.id = :noticeId order by c.placedAt, c.id")
    List<EscalationCall> findForNotice(@Param("noticeId") Long noticeId);
}
