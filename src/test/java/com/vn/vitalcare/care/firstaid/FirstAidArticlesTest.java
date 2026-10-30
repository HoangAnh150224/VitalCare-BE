package com.vn.vitalcare.care.firstaid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.entity.FirstAidArticle;
import com.vn.vitalcare.entity.FirstAidArticleHistory;
import com.vn.vitalcare.entity.FirstAidCategory;
import com.vn.vitalcare.entity.FirstAidStatus;
import com.vn.vitalcare.identity.user.service.UserService;
import com.vn.vitalcare.share.exception.ConflictException;
import com.vn.vitalcare.share.exception.FieldValidationException;
import com.vn.vitalcare.share.security.Permissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class FirstAidArticlesTest {

    private static final long NURSE = 21L;
    private static final long DOCTOR = 22L;

    private FirstAidArticleRepository articles;
    private final List<FirstAidArticleHistory> steps = new ArrayList<>();
    private FirstAidArticles service;
    private FirstAidArticle article;

    @BeforeEach
    void setUp() {
        articles = mock(FirstAidArticleRepository.class);
        FirstAidHistoryRepository history = mock(FirstAidHistoryRepository.class);
        when(history.findSteps(5L)).thenAnswer(call -> steps.reversed());
        when(articles.save(any(FirstAidArticle.class))).thenAnswer(call -> {
            FirstAidArticle saved = call.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(5L);
            }
            article = saved;
            return saved;
        });
        when(articles.lockLive(5L)).thenAnswer(call -> Optional.ofNullable(article));
        when(history.save(any(FirstAidArticleHistory.class))).thenAnswer(call -> {
            steps.add(call.getArgument(0));
            return call.getArgument(0);
        });
        // What the public reads: the steps that published the article's published revision, newest first.
        when(history.findPublished()).thenAnswer(call -> steps.reversed().stream()
                .filter(step -> article.getPublishedRevision() != null
                        && step.getRevision() == article.getPublishedRevision()
                        && step.getToStatus() == FirstAidStatus.PUBLISHED)
                .toList());
        service = new FirstAidArticles(articles, history, mock(UserService.class),
                Clock.fixed(Instant.parse("2026-10-30T03:00:00Z"), ZoneOffset.UTC));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("draft → review → approved by somebody else → published; every step is in the history")
    void wholeLife() {
        signIn(NURSE, Permissions.FIRST_AID_ARTICLES_WRITE);
        service.create(content("Khó thở"));
        service.submit(5L);

        signIn(DOCTOR, Permissions.FIRST_AID_ARTICLES_WRITE, Permissions.FIRST_AID_ARTICLES_REVIEW);
        service.approve(5L, new FirstAidRequests.Decision("Đúng hướng dẫn"));
        service.publish(5L, null);

        assertEquals(FirstAidStatus.PUBLISHED, article.getStatus());
        assertEquals(1, article.getPublishedRevision());
        assertEquals(DOCTOR, article.getReviewerId());
        assertEquals(List.of(FirstAidStatus.DRAFT, FirstAidStatus.PENDING_REVIEW, FirstAidStatus.REVIEWED,
                FirstAidStatus.PUBLISHED), steps.stream().map(FirstAidArticleHistory::getToStatus).toList());
        assertEquals("Khó thở", service.publishedOne(5L).getTitle());
    }

    @Test
    @DisplayName("an author cannot approve or send back their own content")
    void notOwnReview() {
        signIn(DOCTOR, Permissions.FIRST_AID_ARTICLES_WRITE, Permissions.FIRST_AID_ARTICLES_REVIEW);
        service.create(content("Sốt"));
        service.submit(5L);

        assertThrows(ConflictException.class, () -> service.approve(5L, null));
        assertThrows(ConflictException.class, () -> service.reject(5L, new FirstAidRequests.Decision("x")));
        assertTrue(!service.actions(article).contains("approve"));
    }

    @Test
    @DisplayName("somebody who edited the draft cannot approve it either, even though another started it")
    void editorIsNotAReviewer() {
        signIn(DOCTOR, Permissions.FIRST_AID_ARTICLES_WRITE, Permissions.FIRST_AID_ARTICLES_REVIEW);
        service.create(content("Sốt"));
        signIn(NURSE, Permissions.FIRST_AID_ARTICLES_WRITE);
        service.update(5L, content("Sốt (sửa một chữ)"));
        assertEquals(DOCTOR, article.getAuthorId());
        service.submit(5L);

        signIn(DOCTOR, Permissions.FIRST_AID_ARTICLES_REVIEW);
        assertThrows(ConflictException.class, () -> service.approve(5L, null));
        signIn(31L, Permissions.FIRST_AID_ARTICLES_REVIEW);
        service.approve(5L, null);
        assertEquals(FirstAidStatus.REVIEWED, article.getStatus());
    }

    @Test
    @DisplayName("sending back needs a reason, and returns the article to its author as a draft")
    void reject() {
        signIn(NURSE, Permissions.FIRST_AID_ARTICLES_WRITE);
        service.create(content("Ngất"));
        service.submit(5L);

        signIn(DOCTOR, Permissions.FIRST_AID_ARTICLES_REVIEW);
        assertThrows(FieldValidationException.class, () -> service.reject(5L, new FirstAidRequests.Decision(" ")));
        service.reject(5L, new FirstAidRequests.Decision("Thiếu bước gọi 115"));

        assertEquals(FirstAidStatus.DRAFT, article.getStatus());
        assertEquals("Thiếu bước gọi 115", article.getReviewNote());
    }

    @Test
    @DisplayName("editing a published article starts the next revision; the public keeps the published one until it is replaced")
    void editPublished() {
        publishedByReview("Bỏng nhẹ");

        signIn(NURSE, Permissions.FIRST_AID_ARTICLES_WRITE);
        service.update(5L, content("Bỏng nhẹ (bản mới)"));
        assertEquals(FirstAidStatus.DRAFT, article.getStatus());
        assertEquals(2, article.getRevision());
        assertNull(article.getReviewerId());
        assertEquals("Bỏng nhẹ", service.publishedOne(5L).getTitle());

        service.submit(5L);
        signIn(DOCTOR, Permissions.FIRST_AID_ARTICLES_REVIEW);
        service.approve(5L, null);
        service.publish(5L, null);
        assertEquals("Bỏng nhẹ (bản mới)", service.publishedOne(5L).getTitle());
    }

    @Test
    @DisplayName("content waiting for review cannot be edited, and only approved content is published")
    void wrongOrder() {
        signIn(NURSE, Permissions.FIRST_AID_ARTICLES_WRITE);
        service.create(content("Vết thương"));
        signIn(DOCTOR, Permissions.FIRST_AID_ARTICLES_REVIEW);
        assertThrows(ConflictException.class, () -> service.publish(5L, null));
        assertThrows(ConflictException.class, () -> service.approve(5L, null));

        signIn(NURSE, Permissions.FIRST_AID_ARTICLES_WRITE);
        service.submit(5L);
        assertThrows(ConflictException.class, () -> service.update(5L, content("Sửa giữa chừng")));
        assertThrows(ConflictException.class, () -> service.submit(5L));
    }

    @Test
    @DisplayName("archiving takes an article off the public pages")
    void archive() {
        publishedByReview("Khó thở");
        signIn(DOCTOR, Permissions.FIRST_AID_ARTICLES_REVIEW);
        service.archive(5L, new FirstAidRequests.Decision("Đã có bài thay thế"));

        assertEquals(FirstAidStatus.ARCHIVED, article.getStatus());
        assertTrue(service.published(null, null).isEmpty());
        assertThrows(ConflictException.class, () -> service.archive(5L, null));
    }

    @Test
    @DisplayName("the public list filters by category and by words in the title or summary")
    void publicFilters() {
        publishedByReview("Khó thở");
        assertEquals(1, service.published("thở", null).size());
        assertEquals(1, service.published(null, FirstAidCategory.GENERAL).size());
        assertTrue(service.published(null, FirstAidCategory.FEVER).isEmpty());
        assertTrue(service.published("không có", null).isEmpty());
    }

    private void publishedByReview(String title) {
        signIn(NURSE, Permissions.FIRST_AID_ARTICLES_WRITE);
        service.create(content(title));
        service.submit(5L);
        signIn(DOCTOR, Permissions.FIRST_AID_ARTICLES_REVIEW);
        service.approve(5L, null);
        service.publish(5L, null);
    }

    private static FirstAidRequests.Content content(String title) {
        return new FirstAidRequests.Content(title, FirstAidCategory.GENERAL, "Tóm tắt",
                "## Việc cần làm ngay\n1. Bình tĩnh", "Gọi 115 khi nguy hiểm");
    }

    private static void signIn(long userId, String... permissions) {
        Jwt token = Jwt.withTokenValue("test").header("alg", "none").subject(String.valueOf(userId))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(token,
                java.util.Arrays.stream(permissions).map(SimpleGrantedAuthority::new).toList()));
    }
}
