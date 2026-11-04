package com.vn.vitalcare.care.firstaid;

import com.vn.vitalcare.entity.FirstAidArticle;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

/**
 * Not row-level secured: first-aid guidance is the same for every clinic, and
 * who may read or change it is decided by permission alone.
 */
public interface FirstAidArticleRepository extends JpaRepository<FirstAidArticle, Long>,
        JpaSpecificationExecutor<FirstAidArticle> {

    @Query("select a from FirstAidArticle a where a.id = :id and a.deletedAt is null")
    Optional<FirstAidArticle> findLive(@Param("id") Long id);

    /** Locked for a step in its life, so two reviewers deciding at once are seen one after the other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from FirstAidArticle a where a.id = :id and a.deletedAt is null")
    Optional<FirstAidArticle> lockLive(@Param("id") Long id);
}
