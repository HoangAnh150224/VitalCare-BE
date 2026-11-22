package com.vn.vitalcare.it;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

/** View First Aid Guidance and its detail: /api/public/first_aid over the article and history tables. */
class FirstAidIT extends IntegrationTestBase {

    /** An article in the given state; a published one also gets its PUBLISHED history step, as the workflow writes it. */
    private long insertArticle(String title, String category, String status, boolean published) {
        Long id = jdbc.queryForObject("insert into first_aid_article (title, category, summary, body, emergency_warning, "
                + "status, revision, published_revision, published_at, created_at, version) values (?, ?, 'IT summary', "
                + "'## IT\n1. Step', 'Call 115', ?, 1, ?, ?, now(), 0) returning id", Long.class,
                title, category, status, published ? 1 : null, published ? java.sql.Timestamp.from(java.time.Instant.now()) : null);
        if (published) {
            jdbc.update("insert into first_aid_article_history (article_id, revision, from_status, to_status, note, at, "
                    + "title, category, summary, body, emergency_warning) values (?, 1, 'REVIEWED', 'PUBLISHED', 'it', now(), "
                    + "?, ?, 'IT summary', '## IT\n1. Step', 'Call 115')", id, title, category);
        }
        return id;
    }

    @Test
    void it01_seededGuidanceListed() throws Exception {
        mvc.perform(get("/api/public/first_aid"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].title", hasItem("Khó thở")))
                .andExpect(jsonPath("$[*].title", hasItem("Bỏng nhẹ")));
    }

    @Test
    void it02_draftNotListed() throws Exception {
        insertArticle("IT Draft Guidance", "GENERAL", "DRAFT", false);
        mvc.perform(get("/api/public/first_aid"))
                .andExpect(jsonPath("$[*].title", not(hasItem("IT Draft Guidance"))));
    }

    @Test
    void it03_publishedAppears() throws Exception {
        insertArticle("IT Published Guidance", "GENERAL", "PUBLISHED", true);
        mvc.perform(get("/api/public/first_aid"))
                .andExpect(jsonPath("$[*].title", hasItem("IT Published Guidance")));
    }

    @Test
    void it04_categoryFilter() throws Exception {
        mvc.perform(get("/api/public/first_aid").param("category", "fever"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].category", everyItem(is("fever"))))
                .andExpect(jsonPath("$[*].title", hasItem("Sốt cao")));
    }

    @Test
    void it05_searchTitleCaseInsensitive() throws Exception {
        mvc.perform(get("/api/public/first_aid").param("q", "  BỎNG "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].title").value("Bỏng nhẹ"));
    }

    @Test
    void it06_unknownCategoryEmptyNot400() throws Exception {
        mvc.perform(get("/api/public/first_aid").param("category", "xyz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void it07_detailOfPublished() throws Exception {
        long id = insertArticle("IT Detail Guidance", "WOUND", "PUBLISHED", true);
        mvc.perform(get("/api/public/first_aid/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.title").value("IT Detail Guidance"))
                .andExpect(jsonPath("$.category").value("wound"))
                .andExpect(jsonPath("$.body").value("## IT\n1. Step"))
                .andExpect(jsonPath("$.emergencyWarning").value("Call 115"))
                .andExpect(jsonPath("$.publishedAt").exists());
    }

    @Test
    void it08_detailOfDraftIs404() throws Exception {
        long id = insertArticle("IT Draft Detail", "GENERAL", "DRAFT", false);
        mvc.perform(get("/api/public/first_aid/" + id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("First-aid article with id " + id + " was not found"));
    }

    @Test
    void it09_archivedIsGone() throws Exception {
        long id = insertArticle("IT Archived Guidance", "GENERAL", "PUBLISHED", true);
        jdbc.update("update first_aid_article set status = 'ARCHIVED', published_revision = null, published_at = null "
                + "where id = ?", id);
        mvc.perform(get("/api/public/first_aid/" + id)).andExpect(status().isNotFound());
        mvc.perform(get("/api/public/first_aid"))
                .andExpect(jsonPath("$[*].title", not(hasItem("IT Archived Guidance"))));
    }

    @Test
    void it10_deletedArticleGone() throws Exception {
        long id = insertArticle("IT Deleted Guidance", "GENERAL", "PUBLISHED", true);
        jdbc.update("update first_aid_article set deleted_at = now() where id = ?", id);
        mvc.perform(get("/api/public/first_aid/" + id)).andExpect(status().isNotFound());
    }
}
