package com.vn.vitalcare.care.firstaid;

import com.vn.vitalcare.entity.FirstAidArticle;
import com.vn.vitalcare.entity.FirstAidArticleHistory;
import com.vn.vitalcare.entity.FirstAidCategory;
import com.vn.vitalcare.entity.FirstAidStatus;
import com.vn.vitalcare.identity.user.service.UserService;
import com.vn.vitalcare.share.exception.ConflictException;
import com.vn.vitalcare.share.exception.FieldValidationException;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import com.vn.vitalcare.share.security.CurrentUser;
import com.vn.vitalcare.share.security.Permissions;
import com.vn.vitalcare.share.web.ListParams;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * First-aid guidance through its editorial life (SRS 3.17, BR-31):
 * written as a draft, sent for review, approved or sent back by somebody other
 * than its author, published, and archived. Every step is written to the
 * history together with the content at that step.
 *
 * <p>Editing an article that has left the draft stage starts its next
 * revision as a draft. The revision patients read stays up meanwhile: they are
 * served the snapshot taken when it was published, not the row being edited.
 */
@Service
@Transactional(readOnly = true)
public class FirstAidArticles {

    private static final Set<String> SORTABLE = Set.of("id", "title", "status", "updatedAt", "createdAt");
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id"));

    private final FirstAidArticleRepository articles;
    private final FirstAidHistoryRepository history;
    private final UserService userService;
    private final Clock clock;

    public FirstAidArticles(FirstAidArticleRepository articles, FirstAidHistoryRepository history,
                            UserService userService, Clock clock) {
        this.articles = articles;
        this.history = history;
        this.userService = userService;
        this.clock = clock;
    }

    public Page<FirstAidArticle> list(ListParams params) {
        return articles.findAll(FirstAidSpecifications.from(params), params.pageable(SORTABLE, DEFAULT_SORT));
    }

    public FirstAidArticle get(Long id) {
        return articles.findLive(id).orElseThrow(() -> new ResourceNotFoundException("First-aid article", id));
    }

    public List<FirstAidArticleHistory> history(Long id) {
        get(id);
        return history.findSteps(id);
    }

    @Transactional
    public FirstAidArticle create(FirstAidRequests.Content content) {
        FirstAidArticle article = new FirstAidArticle();
        apply(article, content);
        article.setStatus(FirstAidStatus.DRAFT);
        article.setRevision(1);
        article.setAuthorId(CurrentUser.id().orElse(null));
        FirstAidArticle saved = articles.save(article);
        record(saved, null, null);
        return saved;
    }

    /**
     * Changes the content. A draft is changed in place; an article past the
     * draft stage goes back to being a draft, as its next revision, with the
     * editor as its author and no reviewer yet. One waiting for review is left
     * alone until the reviewer has decided.
     */
    @Transactional
    public FirstAidArticle update(Long id, FirstAidRequests.Content content) {
        FirstAidArticle article = lock(id);
        FirstAidStatus from = article.getStatus();
        if (from == FirstAidStatus.PENDING_REVIEW) {
            throw new ConflictException("This content is waiting for review; it can be changed once the reviewer has decided");
        }
        apply(article, content);
        if (from != FirstAidStatus.DRAFT) {
            article.setRevision(article.getRevision() + 1);
            article.setStatus(FirstAidStatus.DRAFT);
            article.setReviewerId(null);
            article.setReviewNote(null);
            // Whoever starts a revision is its author; later edits to the draft keep them.
            article.setAuthorId(CurrentUser.id().orElse(null));
        }
        FirstAidArticle saved = articles.save(article);
        record(saved, from, null);
        return saved;
    }

    @Transactional
    public FirstAidArticle submit(Long id) {
        FirstAidArticle article = lock(id);
        require(article, FirstAidStatus.DRAFT, "Only a draft can be sent for review");
        return move(article, FirstAidStatus.PENDING_REVIEW, null);
    }

    @Transactional
    public FirstAidArticle approve(Long id, FirstAidRequests.Decision decision) {
        FirstAidArticle article = lock(id);
        require(article, FirstAidStatus.PENDING_REVIEW, "Only content waiting for review can be approved");
        requireNotAWriter(article);
        article.setReviewerId(CurrentUser.id().orElse(null));
        article.setReviewNote(note(decision));
        return move(article, FirstAidStatus.REVIEWED, note(decision));
    }

    /** Back to its author as a draft, with the reviewer's reason. */
    @Transactional
    public FirstAidArticle reject(Long id, FirstAidRequests.Decision decision) {
        FirstAidArticle article = lock(id);
        require(article, FirstAidStatus.PENDING_REVIEW, "Only content waiting for review can be sent back");
        requireNotAWriter(article);
        String note = note(decision);
        if (note == null) {
            throw new FieldValidationException("note", "Say why it is sent back");
        }
        article.setReviewerId(CurrentUser.id().orElse(null));
        article.setReviewNote(note);
        return move(article, FirstAidStatus.DRAFT, note);
    }

    /** Out for patients: this revision replaces whichever was published before. */
    @Transactional
    public FirstAidArticle publish(Long id, FirstAidRequests.Decision decision) {
        FirstAidArticle article = lock(id);
        require(article, FirstAidStatus.REVIEWED, "Only approved content can be published");
        article.setPublishedRevision(article.getRevision());
        article.setPublishedAt(OffsetDateTime.now(clock));
        return move(article, FirstAidStatus.PUBLISHED, note(decision));
    }

    /** Off the public pages, whatever stage it was at; kept, with its history. */
    @Transactional
    public FirstAidArticle archive(Long id, FirstAidRequests.Decision decision) {
        FirstAidArticle article = lock(id);
        if (article.getStatus() == FirstAidStatus.ARCHIVED) {
            throw new ConflictException("This content is already archived");
        }
        article.setPublishedRevision(null);
        article.setPublishedAt(null);
        return move(article, FirstAidStatus.ARCHIVED, note(decision));
    }

    /**
     * What the caller may do to the article now, for the screen to offer only
     * those buttons. The endpoints check the same rules again.
     */
    public List<String> actions(FirstAidArticle article) {
        boolean write = CurrentUser.has(Permissions.FIRST_AID_ARTICLES_WRITE);
        boolean review = CurrentUser.has(Permissions.FIRST_AID_ARTICLES_REVIEW);
        boolean ownWork = CurrentUser.id()
                .filter(id -> id.equals(article.getAuthorId()) || writersOf(article).contains(id)).isPresent();
        FirstAidStatus status = article.getStatus();
        List<String> actions = new ArrayList<>();
        if (write && status != FirstAidStatus.PENDING_REVIEW) {
            actions.add("edit");
        }
        if (write && status == FirstAidStatus.DRAFT) {
            actions.add("submit");
        }
        if (review && status == FirstAidStatus.PENDING_REVIEW && !ownWork) {
            actions.add("approve");
            actions.add("reject");
        }
        if (review && status == FirstAidStatus.REVIEWED) {
            actions.add("publish");
        }
        if (review && status != FirstAidStatus.ARCHIVED) {
            actions.add("archive");
        }
        return actions;
    }

    /** Users' full names by id, for the author, reviewer and history columns. */
    public Map<Long, String> namesFor(Collection<Long> userIds) {
        List<Long> ids = userIds.stream().filter(Objects::nonNull).distinct().toList();
        Map<Long, String> names = new HashMap<>();
        if (!ids.isEmpty()) {
            userService.getMany(ids).forEach(user -> names.put(user.getId(), user.getFullName()));
        }
        return names;
    }

    public Map<Long, String> namesForArticles(Collection<FirstAidArticle> list) {
        return namesFor(list.stream()
                .flatMap(article -> Stream.of(article.getAuthorId(), article.getReviewerId()))
                .toList());
    }

    // ---- what everybody reads ----------------------------------------------------------

    /**
     * Every published article as published, newest first; optionally only one
     * category, or those whose title or summary contains {@code query}.
     */
    public List<FirstAidArticleHistory> published(String query, FirstAidCategory category) {
        String term = query == null || query.isBlank() ? null : query.trim().toLowerCase(Locale.ROOT);
        Map<Long, FirstAidArticleHistory> latest = new LinkedHashMap<>();
        for (FirstAidArticleHistory step : history.findPublished()) {
            latest.putIfAbsent(step.getArticleId(), step);
        }
        return latest.values().stream()
                .filter(step -> category == null || step.getCategory() == category)
                .filter(step -> term == null
                        || step.getTitle().toLowerCase(Locale.ROOT).contains(term)
                        || step.getSummary().toLowerCase(Locale.ROOT).contains(term))
                .toList();
    }

    public FirstAidArticleHistory publishedOne(Long id) {
        return published(null, null).stream()
                .filter(step -> step.getArticleId().equals(id))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("First-aid article", id));
    }

    // ---- internals ------------------------------------------------------------------------

    private FirstAidArticle lock(Long id) {
        return articles.lockLive(id).orElseThrow(() -> new ResourceNotFoundException("First-aid article", id));
    }

    private FirstAidArticle move(FirstAidArticle article, FirstAidStatus to, String note) {
        FirstAidStatus from = article.getStatus();
        article.setStatus(to);
        FirstAidArticle saved = articles.save(article);
        record(saved, from, note);
        return saved;
    }

    private void record(FirstAidArticle article, FirstAidStatus from, String note) {
        history.save(FirstAidArticleHistory.of(article, from, CurrentUser.id().orElse(null), note,
                OffsetDateTime.now(clock)));
    }

    private static void require(FirstAidArticle article, FirstAidStatus expected, String message) {
        if (article.getStatus() != expected) {
            throw new ConflictException(message);
        }
    }

    /**
     * BR-31: reviewed by somebody who did not write it — not its author, and
     * not anybody who edited this revision's draft either, or a second pair of
     * hands on the draft would let the first approve their own words.
     */
    private void requireNotAWriter(FirstAidArticle article) {
        Optional<Long> caller = CurrentUser.id();
        if (caller.isEmpty()) {
            return;
        }
        if (caller.get().equals(article.getAuthorId()) || writersOf(article).contains(caller.get())) {
            throw new ConflictException("Content is reviewed by somebody other than its author");
        }
    }

    /** Everybody who saved this revision as a draft. */
    private Set<Long> writersOf(FirstAidArticle article) {
        return history.findSteps(article.getId()).stream()
                .filter(step -> step.getRevision() == article.getRevision() && step.getToStatus() == FirstAidStatus.DRAFT
                        && step.getFromStatus() != FirstAidStatus.PENDING_REVIEW)
                .map(FirstAidArticleHistory::getActorId)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
    }

    private static void apply(FirstAidArticle article, FirstAidRequests.Content content) {
        article.setTitle(content.title().trim());
        article.setCategory(content.category());
        article.setSummary(content.summary().trim());
        article.setBody(content.body().strip());
        article.setEmergencyWarning(content.emergencyWarning().trim());
    }

    private static String note(FirstAidRequests.Decision decision) {
        if (decision == null || decision.note() == null || decision.note().isBlank()) {
            return null;
        }
        return decision.note().trim();
    }
}
