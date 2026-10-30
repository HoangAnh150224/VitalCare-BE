package com.vn.vitalcare.care.publicsite;

import com.vn.vitalcare.care.firstaid.FirstAidArticles;
import com.vn.vitalcare.care.firstaid.FirstAidResponses;
import com.vn.vitalcare.entity.FirstAidCategory;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public site's reads, open to everybody (see {@code SecurityConfig}):
 * clinics, the service price list, and published first-aid guidance. Every
 * answer is a public DTO, never a staff one.
 */
@RestController
@RequestMapping("/api/public")
public class PublicSiteController {

    private final PublicSite site;
    private final FirstAidArticles firstAid;

    public PublicSiteController(PublicSite site, FirstAidArticles firstAid) {
        this.site = site;
        this.firstAid = firstAid;
    }

    @GetMapping("/clinics")
    public List<PublicResponses.Clinic> clinics() {
        return site.clinics();
    }

    @GetMapping("/clinics/{id}")
    public PublicResponses.ClinicDetail clinic(@PathVariable UUID id) {
        return site.clinic(id);
    }

    @GetMapping("/services")
    public List<PublicResponses.Service> services() {
        return site.services();
    }

    /** An unknown category matches nothing rather than failing: a stale link shows an empty list. */
    @GetMapping("/first_aid")
    public List<FirstAidResponses.Published> firstAid(@RequestParam(required = false) String q,
                                                      @RequestParam(required = false) String category) {
        FirstAidCategory wanted;
        try {
            wanted = FirstAidCategory.from(category);
        } catch (IllegalArgumentException e) {
            return List.of();
        }
        return firstAid.published(q, wanted).stream().map(FirstAidResponses.Published::from).toList();
    }

    @GetMapping("/first_aid/{id}")
    public FirstAidResponses.Published firstAidArticle(@PathVariable Long id) {
        return FirstAidResponses.Published.from(firstAid.publishedOne(id));
    }
}
