package com.vn.vitalcare.care.alert;

import com.vn.vitalcare.entity.AlertAction;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AlertActionRepository extends JpaRepository<AlertAction, Long> {

    List<AlertAction> findByAlertIdOrderByOccurredAtAscIdAsc(Long alertId);

    /** Abnormal readings: each reading that raised or fed an alert of the given ones. */
    @EntityGraph(attributePaths = {"alert", "alert.customer", "vitalSign"})
    @Query("select x from AlertAction x where x.vitalSign is not null and x.alert.id in"
            + " (select a.id from Alert a where a.id in :alertIds) order by x.occurredAt desc, x.id desc")
    Page<AlertAction> findReadings(@Param("alertIds") List<Long> alertIds, Pageable pageable);
}
