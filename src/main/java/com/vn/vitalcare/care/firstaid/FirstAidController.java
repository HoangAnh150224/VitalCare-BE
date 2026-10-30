package com.vn.vitalcare.care.firstaid;

import com.vn.vitalcare.entity.FirstAidArticle;
import com.vn.vitalcare.entity.FirstAidArticleHistory;
import com.vn.vitalcare.share.security.Permissions;
import com.vn.vitalcare.share.web.ListParams;
import com.vn.vitalcare.share.web.ListResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** First-aid content as staff manage it; what is published is read through {@code /api/public}. */
@RestController
@RequestMapping("/api/first_aid_articles")
public class FirstAidController {

    private final FirstAidArticles service;

    public FirstAidController(FirstAidArticles service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.FIRST_AID_ARTICLES_READ + "')")
    public ResponseEntity<List<FirstAidResponses.Article>> list(@RequestParam MultiValueMap<String, String> query) {
        Page<FirstAidArticle> page = service.list(new ListParams(query));
        return ListResponse.of(responses(page.getContent()), page.getTotalElements());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.FIRST_AID_ARTICLES_READ + "')")
    public FirstAidResponses.Article get(@PathVariable Long id) {
        return response(service.get(id));
    }

    @GetMapping("/{id}/history")
    @PreAuthorize("hasAuthority('" + Permissions.FIRST_AID_ARTICLES_READ + "')")
    public List<FirstAidResponses.Step> history(@PathVariable Long id) {
        List<FirstAidArticleHistory> steps = service.history(id);
        Map<Long, String> names = service.namesFor(steps.stream().map(FirstAidArticleHistory::getActorId).toList());
        return steps.stream().map(step -> FirstAidResponses.Step.from(step, names)).toList();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + Permissions.FIRST_AID_ARTICLES_WRITE + "')")
    public ResponseEntity<FirstAidResponses.Article> create(@Valid @RequestBody FirstAidRequests.Content request) {
        FirstAidArticle created = service.create(request);
        return ResponseEntity.created(URI.create("/api/first_aid_articles/" + created.getId())).body(response(created));
    }

    @RequestMapping(value = "/{id}", method = {RequestMethod.PATCH, RequestMethod.PUT})
    @PreAuthorize("hasAuthority('" + Permissions.FIRST_AID_ARTICLES_WRITE + "')")
    public FirstAidResponses.Article update(@PathVariable Long id, @Valid @RequestBody FirstAidRequests.Content request) {
        return response(service.update(id, request));
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAuthority('" + Permissions.FIRST_AID_ARTICLES_WRITE + "')")
    public FirstAidResponses.Article submit(@PathVariable Long id) {
        return response(service.submit(id));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('" + Permissions.FIRST_AID_ARTICLES_REVIEW + "')")
    public FirstAidResponses.Article approve(@PathVariable Long id,
                                             @Valid @RequestBody(required = false) FirstAidRequests.Decision request) {
        return response(service.approve(id, request));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('" + Permissions.FIRST_AID_ARTICLES_REVIEW + "')")
    public FirstAidResponses.Article reject(@PathVariable Long id,
                                            @Valid @RequestBody(required = false) FirstAidRequests.Decision request) {
        return response(service.reject(id, request));
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize("hasAuthority('" + Permissions.FIRST_AID_ARTICLES_REVIEW + "')")
    public FirstAidResponses.Article publish(@PathVariable Long id,
                                             @Valid @RequestBody(required = false) FirstAidRequests.Decision request) {
        return response(service.publish(id, request));
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasAuthority('" + Permissions.FIRST_AID_ARTICLES_REVIEW + "')")
    public FirstAidResponses.Article archive(@PathVariable Long id,
                                             @Valid @RequestBody(required = false) FirstAidRequests.Decision request) {
        return response(service.archive(id, request));
    }

    private FirstAidResponses.Article response(FirstAidArticle article) {
        return responses(List.of(article)).getFirst();
    }

    private List<FirstAidResponses.Article> responses(List<FirstAidArticle> list) {
        Map<Long, String> names = service.namesForArticles(list);
        return list.stream()
                .map(article -> FirstAidResponses.Article.from(article, names, service.actions(article)))
                .toList();
    }
}
