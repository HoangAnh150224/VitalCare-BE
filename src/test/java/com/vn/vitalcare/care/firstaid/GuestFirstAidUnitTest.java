package com.vn.vitalcare.care.firstaid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.entity.FirstAidArticleHistory;
import com.vn.vitalcare.entity.FirstAidCategory;
import com.vn.vitalcare.identity.user.service.UserService;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** UC View First Aid and View First Aid Detail: the units behind /api/public/first_aid. */
class GuestFirstAidUnitTest {

    private FirstAidHistoryRepository history;
    private FirstAidArticles articles;
    /** What findPublished answers: newest step first. */
    private final List<FirstAidArticleHistory> published = new ArrayList<>();

    @BeforeEach
    void setUp() {
        history = mock(FirstAidHistoryRepository.class);
        when(history.findPublished()).thenAnswer(call -> published);
        articles = new FirstAidArticles(mock(FirstAidArticleRepository.class), history, mock(UserService.class),
                Clock.systemUTC(), event -> { });
    }

    private FirstAidArticleHistory step(long articleId, int revision, String title, String summary,
                                        FirstAidCategory category) {
        FirstAidArticleHistory step = mock(FirstAidArticleHistory.class);
        when(step.getArticleId()).thenReturn(articleId);
        when(step.getRevision()).thenReturn(revision);
        when(step.getTitle()).thenReturn(title);
        when(step.getSummary()).thenReturn(summary);
        when(step.getCategory()).thenReturn(category);
        when(step.getBody()).thenReturn("## Làm ngay\n1. Bước một");
        when(step.getEmergencyWarning()).thenReturn("Gọi 115 nếu khó thở");
        when(step.getAt()).thenReturn(OffsetDateTime.parse("2026-10-30T08:00:00+07:00"));
        return step;
    }

    @Nested
    @DisplayName("FirstAidCategory.from")
    class CategoryFrom {

        @Test void lowerCase() {
            assertEquals(FirstAidCategory.FEVER, FirstAidCategory.from("fever"));
        }

        @Test void spacesAndCase() {
            assertEquals(FirstAidCategory.WOUND, FirstAidCategory.from(" Wound "));
        }

        @Test void nullOrBlank() {
            assertNull(FirstAidCategory.from(null));
            assertNull(FirstAidCategory.from("  "));
        }

        @Test void unknown() {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> FirstAidCategory.from("xyz"));
            assertEquals("Unknown first-aid category: xyz", e.getMessage());
        }

        @Test void jsonValue() {
            assertEquals("environment", FirstAidCategory.ENVIRONMENT.getValue());
        }
    }

    @Nested
    @DisplayName("FirstAidArticles.published(query, category)")
    class Published {

        @Test void latestStepPerArticle() {
            FirstAidArticleHistory newer = step(1L, 2, "Sốt cao (bản 2)", "Hạ sốt", FirstAidCategory.FEVER);
            FirstAidArticleHistory older = step(1L, 1, "Sốt cao (bản 1)", "Hạ sốt", FirstAidCategory.FEVER);
            FirstAidArticleHistory other = step(2L, 1, "Bỏng nhẹ", "Làm mát vết bỏng", FirstAidCategory.WOUND);
            published.addAll(List.of(newer, other, older));
            assertEquals(List.of(newer, other), articles.published(null, null));
        }

        @Test void byCategory() {
            FirstAidArticleHistory fever = step(1L, 1, "Sốt cao", "Hạ sốt", FirstAidCategory.FEVER);
            FirstAidArticleHistory burn = step(2L, 1, "Bỏng nhẹ", "Làm mát", FirstAidCategory.WOUND);
            published.addAll(List.of(fever, burn));
            assertEquals(List.of(burn), articles.published(null, FirstAidCategory.WOUND));
        }

        @Test void searchTitleIgnoringCaseAndSpaces() {
            FirstAidArticleHistory burn = step(2L, 1, "Bỏng nhẹ", "Làm mát", FirstAidCategory.WOUND);
            published.addAll(List.of(step(1L, 1, "Sốt cao", "Hạ sốt", FirstAidCategory.FEVER), burn));
            assertEquals(List.of(burn), articles.published("  BỎNG ", null));
        }

        @Test void searchSummary() {
            FirstAidArticleHistory burn = step(2L, 1, "Bỏng nhẹ", "Làm mát bằng nước sạch", FirstAidCategory.WOUND);
            published.add(burn);
            assertEquals(List.of(burn), articles.published("nước sạch", null));
        }

        @Test void blankQueryIsNoFilter() {
            published.addAll(List.of(step(1L, 1, "Sốt cao", "Hạ sốt", FirstAidCategory.FEVER),
                    step(2L, 1, "Bỏng nhẹ", "Làm mát", FirstAidCategory.WOUND)));
            assertEquals(2, articles.published("   ", null).size());
        }

        @Test void noMatch() {
            published.add(step(1L, 1, "Sốt cao", "Hạ sốt", FirstAidCategory.FEVER));
            assertEquals(List.of(), articles.published("rắn cắn", null));
            assertEquals(List.of(), articles.published(null, FirstAidCategory.RESPIRATORY));
        }

        @Test void nothingPublished() {
            assertEquals(List.of(), articles.published(null, null));
        }
    }

    @Nested
    @DisplayName("FirstAidArticles.publishedOne(id) and FirstAidResponses.Published.from")
    class PublishedOne {

        @Test void found() {
            FirstAidArticleHistory burn = step(2L, 3, "Bỏng nhẹ", "Làm mát", FirstAidCategory.WOUND);
            published.addAll(List.of(step(1L, 1, "Sốt cao", "Hạ sốt", FirstAidCategory.FEVER), burn));
            assertSame(burn, articles.publishedOne(2L));
        }

        @Test void notPublished() {
            published.add(step(1L, 1, "Sốt cao", "Hạ sốt", FirstAidCategory.FEVER));
            ResourceNotFoundException e = assertThrows(ResourceNotFoundException.class,
                    () -> articles.publishedOne(99L));
            assertEquals("First-aid article with id 99 was not found", e.getMessage());
        }

        @Test void newestRevisionServed() {
            FirstAidArticleHistory newer = step(2L, 4, "Bỏng nhẹ (sửa)", "Làm mát", FirstAidCategory.WOUND);
            published.addAll(List.of(newer, step(2L, 3, "Bỏng nhẹ", "Làm mát", FirstAidCategory.WOUND)));
            assertSame(newer, articles.publishedOne(2L));
        }

        @Test void responseFields() {
            FirstAidResponses.Published p = FirstAidResponses.Published.from(
                    step(2L, 3, "Bỏng nhẹ", "Làm mát", FirstAidCategory.WOUND));
            assertEquals(2L, p.id());
            assertEquals("Bỏng nhẹ", p.title());
            assertEquals(FirstAidCategory.WOUND, p.category());
            assertEquals("Làm mát", p.summary());
            assertEquals("## Làm ngay\n1. Bước một", p.body());
            assertEquals("Gọi 115 nếu khó thở", p.emergencyWarning());
            assertEquals(OffsetDateTime.parse("2026-10-30T08:00:00+07:00"), p.publishedAt());
        }

        @Test void noWarning() {
            FirstAidArticleHistory s = step(2L, 3, "Bỏng nhẹ", "Làm mát", FirstAidCategory.WOUND);
            when(s.getEmergencyWarning()).thenReturn(null);
            assertNull(FirstAidResponses.Published.from(s).emergencyWarning());
        }
    }
}
