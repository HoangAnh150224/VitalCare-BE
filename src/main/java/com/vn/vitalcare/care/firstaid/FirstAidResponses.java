package com.vn.vitalcare.care.firstaid;

import com.vn.vitalcare.entity.FirstAidArticle;
import com.vn.vitalcare.entity.FirstAidArticleHistory;
import com.vn.vitalcare.entity.FirstAidCategory;
import com.vn.vitalcare.entity.FirstAidStatus;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** What the first-aid endpoints answer. */
public final class FirstAidResponses {

    private FirstAidResponses() {
    }

    /**
     * An article as staff work it.
     *
     * @param actions the steps the caller may take on it now: {@code edit},
     *                {@code submit}, {@code approve}, {@code reject},
     *                {@code publish}, {@code archive}
     */
    public record Article(
            Long id,
            String title,
            FirstAidCategory category,
            String summary,
            String body,
            String emergencyWarning,
            FirstAidStatus status,
            int revision,
            Long authorId,
            String authorName,
            String reviewerName,
            String reviewNote,
            Integer publishedRevision,
            OffsetDateTime publishedAt,
            Instant updatedAt,
            List<String> actions) {

        static Article from(FirstAidArticle article, Map<Long, String> names, List<String> actions) {
            return new Article(article.getId(), article.getTitle(), article.getCategory(), article.getSummary(),
                    article.getBody(), article.getEmergencyWarning(), article.getStatus(), article.getRevision(),
                    article.getAuthorId(), names.get(article.getAuthorId()), names.get(article.getReviewerId()),
                    article.getReviewNote(), article.getPublishedRevision(), article.getPublishedAt(),
                    article.getUpdatedAt() != null ? article.getUpdatedAt() : article.getCreatedAt(), actions);
        }
    }

    /** One step of an article's history, with the content it left behind. */
    public record Step(
            Long id,
            int revision,
            FirstAidStatus fromStatus,
            FirstAidStatus toStatus,
            String actorName,
            String note,
            OffsetDateTime at,
            String title,
            FirstAidCategory category,
            String summary,
            String body,
            String emergencyWarning) {

        static Step from(FirstAidArticleHistory step, Map<Long, String> names) {
            return new Step(step.getId(), step.getRevision(), step.getFromStatus(), step.getToStatus(),
                    names.get(step.getActorId()), step.getNote(), step.getAt(), step.getTitle(), step.getCategory(),
                    step.getSummary(), step.getBody(), step.getEmergencyWarning());
        }
    }

    /** A published article as anybody reads it: the published revision's content, nothing about who wrote it. */
    public record Published(
            Long id,
            String title,
            FirstAidCategory category,
            String summary,
            String body,
            String emergencyWarning,
            OffsetDateTime publishedAt) {

        public static Published from(FirstAidArticleHistory step) {
            return new Published(step.getArticleId(), step.getTitle(), step.getCategory(), step.getSummary(),
                    step.getBody(), step.getEmergencyWarning(), step.getAt());
        }
    }
}
