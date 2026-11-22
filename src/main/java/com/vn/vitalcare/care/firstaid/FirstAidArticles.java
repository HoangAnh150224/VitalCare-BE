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
    private final org.springframework.context.ApplicationEventPublisher events;

    public FirstAidArticles(FirstAidArticleRepository articles, FirstAidHistoryRepository history,
                            UserService userService, Clock clock,
                            org.springframework.context.ApplicationEventPublisher events) {
        this.events = events;
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
        ensureTitleAvailable(content.title(), null);
        FirstAidArticle article = new FirstAidArticle();
        apply(article, content);
        article.setStatus(FirstAidStatus.DRAFT);
        article.setRevision(1);
        article.setAuthorId(CurrentUser.id().orElse(null));
        FirstAidArticle saved = articles.save(article);
        record(saved, null, null);
        events.publishEvent(new com.vn.vitalcare.share.audit.AuditEvent("first_aid_articles", saved.getId(), "create",
                null, state(saved)));
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
        if (from == FirstAidStatus.PAUSED) {
            throw new ConflictException("Vui lòng mở lại nội dung trước khi chỉnh sửa.");
        }
        if (from == FirstAidStatus.PENDING_REVIEW) {
            throw new ConflictException("This content is waiting for review; it can be changed once the reviewer has decided");
        }
        ensureTitleAvailable(content.title(), id);
        Map<String, Object> before = state(article);
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
        // Only what this edit changed, as the staff log does (KAN-133).
        Map<String, Object> changedBefore = new LinkedHashMap<>();
        Map<String, Object> changedAfter = new LinkedHashMap<>();
        state(saved).forEach((field, value) -> {
            if (!Objects.equals(before.get(field), value)) {
                changedBefore.put(field, before.get(field));
                changedAfter.put(field, value);
            }
        });
        if (!changedAfter.isEmpty()) {
            events.publishEvent(new com.vn.vitalcare.share.audit.AuditEvent("first_aid_articles", saved.getId(), "update",
                    changedBefore, changedAfter));
        }
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
        if (!(article.getStatus() == FirstAidStatus.DRAFT && canPublishOwnDraft(article))) {
            require(article, FirstAidStatus.REVIEWED, "Only approved content can be published");
        }
        article.setPublishedRevision(article.getRevision());
        article.setPublishedAt(OffsetDateTime.now(clock));
        return move(article, FirstAidStatus.PUBLISHED, note(decision));
    }

    @Transactional
    public FirstAidArticle pause(Long id) {
        FirstAidArticle article = lock(id);
        require(article, FirstAidStatus.PUBLISHED, "Chỉ có thể tạm ngưng nội dung đã xuất bản.");
        return move(article, FirstAidStatus.PAUSED, "Tạm ngưng hiển thị nội dung");
    }

    @Transactional
    public FirstAidArticle resume(Long id) {
        FirstAidArticle article = lock(id);
        require(article, FirstAidStatus.PAUSED, "Nội dung không ở trạng thái tạm ngưng.");
        return move(article, FirstAidStatus.PUBLISHED, "Mở lại nội dung");
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
    private boolean canPublishOwnDraft(FirstAidArticle article) {
        return CurrentUser.has("ROLE_CLINIC_ADMIN")
                && CurrentUser.has(Permissions.FIRST_AID_ARTICLES_WRITE)
                && CurrentUser.has(Permissions.FIRST_AID_ARTICLES_REVIEW)
                && CurrentUser.id().filter(id -> id.equals(article.getAuthorId())).isPresent();
    }

    public List<String> actions(FirstAidArticle article) {
        boolean write = CurrentUser.has(Permissions.FIRST_AID_ARTICLES_WRITE);
        boolean review = CurrentUser.has(Permissions.FIRST_AID_ARTICLES_REVIEW);
        boolean ownWork = CurrentUser.id()
                .filter(id -> id.equals(article.getAuthorId()) || writersOf(article).contains(id)).isPresent();
        FirstAidStatus status = article.getStatus();
        List<String> actions = new ArrayList<>();
        if (write && status != FirstAidStatus.PENDING_REVIEW && status != FirstAidStatus.PAUSED) {
            actions.add("edit");
        }
        if (write && status == FirstAidStatus.DRAFT && !canPublishOwnDraft(article)) {
            actions.add("submit");
        }
        if (review && status == FirstAidStatus.PENDING_REVIEW && !ownWork) {
            actions.add("approve");
            actions.add("reject");
        }
        if (review && (status == FirstAidStatus.REVIEWED || (status == FirstAidStatus.DRAFT && canPublishOwnDraft(article)))) {
            actions.add("publish");
        }
        if (review && status != FirstAidStatus.ARCHIVED) {
            actions.add("archive");
        }
        if (review && status == FirstAidStatus.PUBLISHED) actions.add("pause");
        if (review && status == FirstAidStatus.PAUSED) actions.add("resume");
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
        // The activity log (KAN-133): every step, with the status it left and the one it reached.
        String action = switch (to) {
            case PENDING_REVIEW -> "submit";
            case REVIEWED -> "approve";
            case DRAFT -> "reject";
            case PUBLISHED -> from == FirstAidStatus.PAUSED ? "resume" : "publish";
            case PAUSED -> "pause";
            case ARCHIVED -> "archive";
        };
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", to.getValue());
        if (note != null) {
            after.put("note", note);
        }
        events.publishEvent(new com.vn.vitalcare.share.audit.AuditEvent("first_aid_articles", saved.getId(), action,
                Map.of("status", from.getValue()), after));
        return saved;
    }

    /** The parts of an article the activity log shows; nulls kept, so a cleared field reads as cleared. */
    private static Map<String, Object> state(FirstAidArticle article) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("title", article.getTitle());
        fields.put("category", article.getCategory() == null ? null : article.getCategory().name());
        fields.put("summary", article.getSummary());
        fields.put("body", article.getBody());
        fields.put("emergencyWarning", article.getEmergencyWarning());
        fields.put("videoUrl", article.getVideoUrl());
        fields.put("status", article.getStatus() == null ? null : article.getStatus().getValue());
        fields.put("revision", article.getRevision());
        return fields;
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

    /**
     * KAN-129: two articles in use may not share a title (letter case and outer
     * spaces aside); an archived one gives its title up. Checked here, with no
     * unique index behind it: titles saved before this rule already repeat.
     */
    private void ensureTitleAvailable(String title, Long selfId) {
        // No article has id -1, so a new one is compared with all of them.
        if (articles.titleTaken(title.trim(), FirstAidStatus.ARCHIVED, selfId == null ? -1L : selfId)) {
            throw new FieldValidationException("title", "Tiêu đề đã tồn tại. Hãy đặt tiêu đề khác.");
        }
    }

    private static void apply(FirstAidArticle article, FirstAidRequests.Content content) {
        article.setTitle(content.title().trim());
        article.setCategory(content.category());
        article.setSummary(content.summary().trim());
        article.setBody(content.body().strip());
        article.setEmergencyWarning(content.emergencyWarning().trim());
        article.setVideoUrl(FirstAidVideos.embed(content.videoUrl()));
    }

    private static String note(FirstAidRequests.Decision decision) {
        if (decision == null || decision.note() == null || decision.note().isBlank()) {
            return null;
        }
        return decision.note().trim();
    }
}
