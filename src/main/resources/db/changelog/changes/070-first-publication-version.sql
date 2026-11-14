--liquibase formatted sql
--changeset vitalcare:070-first-publication-version
-- Correct only untouched seed articles whose first publication was numbered 2.
-- Keep every history row and its timestamp; publishing is a state change of v1.
WITH candidates AS (
    SELECT a.id
    FROM first_aid_article a
    WHERE a.deleted_at IS NULL AND a.status = 'PUBLISHED'
      AND a.revision = 2 AND a.published_revision = 2
      AND EXISTS (
          SELECT 1 FROM first_aid_article_history h
          WHERE h.article_id = a.id AND h.revision = 2
            AND h.to_status = 'PUBLISHED' AND h.actor_id IS NULL
            AND h.note = 'Xuất bản lại nội dung sơ cứu của nhóm'
            AND h.at = a.published_at
            AND h.title = a.title AND h.body = a.body AND h.summary = a.summary
            AND h.category = a.category AND h.emergency_warning = a.emergency_warning
            AND h.video_url IS NOT DISTINCT FROM a.video_url
      )
      AND NOT EXISTS (
          SELECT 1 FROM first_aid_article_history h WHERE h.article_id = a.id
            AND (h.actor_id IS NOT NULL OR h.revision > 2
                 OR (h.revision = 1 AND h.to_status = 'PUBLISHED'))
      )
), corrected AS (
    UPDATE first_aid_article a
    SET revision = 1, published_revision = 1, version = version + 1
    FROM candidates c WHERE a.id = c.id
    RETURNING a.id
)
UPDATE first_aid_article_history h SET revision = 1
FROM corrected c WHERE h.article_id = c.id AND h.revision = 2;
