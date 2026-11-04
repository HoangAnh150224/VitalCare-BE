package com.vn.vitalcare.care.firstaid;

import com.vn.vitalcare.entity.FirstAidArticleHistory;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FirstAidHistoryRepository extends JpaRepository<FirstAidArticleHistory, Long> {

    /** An article's steps, newest first. */
    @Query("select h from FirstAidArticleHistory h where h.articleId = :articleId order by h.id desc")
    List<FirstAidArticleHistory> findSteps(@Param("articleId") Long articleId);

    /**
     * What patients read: for each live article with a published revision, the
     * step that published it. Newest step first, so a caller keeping the first
     * per article keeps the latest should a revision ever be published twice.
     */
    @Query("select h from FirstAidArticleHistory h, FirstAidArticle a "
            + "where h.articleId = a.id and a.deletedAt is null and a.publishedRevision is not null "
            + "and h.revision = a.publishedRevision "
            + "and h.toStatus = com.vn.vitalcare.entity.FirstAidStatus.PUBLISHED "
            + "order by h.id desc")
    List<FirstAidArticleHistory> findPublished();
}
