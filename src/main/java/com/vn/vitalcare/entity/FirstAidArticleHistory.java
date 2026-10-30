package com.vn.vitalcare.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * One step in a first-aid article's life, with the content as it stood then
 * (BR-31: historical revisions are kept, never overwritten). Append-only: no
 * setters after construction, no audit columns to update, no soft delete.
 *
 * <p>The snapshot on the step that published a revision is what patients
 * read for as long as that revision stays published.
 */
@Entity
@Table(name = "first_aid_article_history")
public class FirstAidArticleHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "article_id", nullable = false, updatable = false)
    private Long articleId;

    @Column(name = "revision", nullable = false, updatable = false)
    private int revision;

    /** Null on the step that created the article. */
    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 16, updatable = false)
    private FirstAidStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 16, updatable = false)
    private FirstAidStatus toStatus;

    @Column(name = "actor_id", updatable = false)
    private Long actorId;

    @Column(name = "note", length = 1000, updatable = false)
    private String note;

    @Column(name = "at", nullable = false, updatable = false)
    private OffsetDateTime at;

    @Column(name = "title", nullable = false, length = 200, updatable = false)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 32, updatable = false)
    private FirstAidCategory category;

    @Column(name = "summary", nullable = false, length = 500, updatable = false)
    private String summary;

    @Column(name = "body", nullable = false, columnDefinition = "text", updatable = false)
    private String body;

    @Column(name = "emergency_warning", nullable = false, length = 1000, updatable = false)
    private String emergencyWarning;

    protected FirstAidArticleHistory() {
    }

    /** The article as it stands now, moved from {@code from} by {@code actorId}. */
    public static FirstAidArticleHistory of(FirstAidArticle article, FirstAidStatus from, Long actorId, String note,
                                            OffsetDateTime at) {
        FirstAidArticleHistory step = new FirstAidArticleHistory();
        step.articleId = article.getId();
        step.revision = article.getRevision();
        step.fromStatus = from;
        step.toStatus = article.getStatus();
        step.actorId = actorId;
        step.note = note;
        step.at = at;
        step.title = article.getTitle();
        step.category = article.getCategory();
        step.summary = article.getSummary();
        step.body = article.getBody();
        step.emergencyWarning = article.getEmergencyWarning();
        return step;
    }

    public Long getId() {
        return id;
    }

    public Long getArticleId() {
        return articleId;
    }

    public int getRevision() {
        return revision;
    }

    public FirstAidStatus getFromStatus() {
        return fromStatus;
    }

    public FirstAidStatus getToStatus() {
        return toStatus;
    }

    public Long getActorId() {
        return actorId;
    }

    public String getNote() {
        return note;
    }

    public OffsetDateTime getAt() {
        return at;
    }

    public String getTitle() {
        return title;
    }

    public FirstAidCategory getCategory() {
        return category;
    }

    public String getSummary() {
        return summary;
    }

    public String getBody() {
        return body;
    }

    public String getEmergencyWarning() {
        return emergencyWarning;
    }
}
