package com.vn.vitalcare.care.firstaid;

import com.vn.vitalcare.entity.FirstAidArticle;
import com.vn.vitalcare.entity.FirstAidStatus;
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

    /**
     * KAN-129: whether another article that is neither deleted nor archived already
     * carries this title, whatever its letter case. {@code selfId} is the article being
     * saved, left out of the search; an id no article has for a new one.
     */
    @Query("select count(a) > 0 from FirstAidArticle a where lower(trim(a.title)) = lower(:title) "
            + "and a.deletedAt is null and a.status <> :archived and a.id <> :selfId")
    boolean titleTaken(@Param("title") String title, @Param("archived") FirstAidStatus archived,
                       @Param("selfId") Long selfId);
}
