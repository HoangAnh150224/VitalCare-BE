package com.vn.vitalcare.entity;

import com.vn.vitalcare.share.data.BaseEntity;
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
 * A piece of first-aid guidance as staff are writing it (SRS 3.17).
 *
 * <p>The columns hold the revision being worked on. What patients read is not
 * this row but the snapshot taken when {@link #publishedRevision} was
 * published, in {@link FirstAidArticleHistory}: editing a published article
 * starts the next revision here while the published one stays up until the
 * new one is reviewed and published in its turn.
 *
 * <p>{@code revision} rather than {@code version}: {@code version} is the
 * optimistic-lock column every {@link BaseEntity} already has.
 */
@Entity
@Table(name = "first_aid_article")
public class FirstAidArticle extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 32)
    private FirstAidCategory category;

    @Column(name = "summary", nullable = false, length = 500)
    private String summary;

    @Column(name = "body", nullable = false, columnDefinition = "text")
    private String body;

    /** When to stop reading and get emergency help; shown above the steps. */
    @Column(name = "emergency_warning", nullable = false, length = 1000)
    private String emergencyWarning;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private FirstAidStatus status = FirstAidStatus.DRAFT;

    @Column(name = "revision", nullable = false)
    private int revision = 1;

    /** Who wrote the revision being worked on; a reviewer may not be them. */
    @Column(name = "author_id")
    private Long authorId;

    @Column(name = "reviewer_id")
    private Long reviewerId;

    @Column(name = "review_note", length = 1000)
    private String reviewNote;

    /** The revision patients read; null while nothing is published. */
    @Column(name = "published_revision")
    private Integer publishedRevision;

    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    public FirstAidArticle() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public FirstAidCategory getCategory() {
        return category;
    }

    public void setCategory(FirstAidCategory category) {
        this.category = category;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public String getEmergencyWarning() {
        return emergencyWarning;
    }

    public void setEmergencyWarning(String emergencyWarning) {
        this.emergencyWarning = emergencyWarning;
    }

    public FirstAidStatus getStatus() {
        return status;
    }

    public void setStatus(FirstAidStatus status) {
        this.status = status;
    }

    public int getRevision() {
        return revision;
    }

    public void setRevision(int revision) {
        this.revision = revision;
    }

    public Long getAuthorId() {
        return authorId;
    }

    public void setAuthorId(Long authorId) {
        this.authorId = authorId;
    }

    public Long getReviewerId() {
        return reviewerId;
    }

    public void setReviewerId(Long reviewerId) {
        this.reviewerId = reviewerId;
    }

    public String getReviewNote() {
        return reviewNote;
    }

    public void setReviewNote(String reviewNote) {
        this.reviewNote = reviewNote;
    }

    public Integer getPublishedRevision() {
        return publishedRevision;
    }

    public void setPublishedRevision(Integer publishedRevision) {
        this.publishedRevision = publishedRevision;
    }

    public OffsetDateTime getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(OffsetDateTime publishedAt) {
        this.publishedAt = publishedAt;
    }
}
