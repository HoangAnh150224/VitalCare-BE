package com.vn.vitalcare.care.serviceorder;

import com.vn.vitalcare.entity.ServiceOrder;
import com.vn.vitalcare.share.security.Permissions;
import com.vn.vitalcare.share.web.ListParams;
import com.vn.vitalcare.share.web.ListResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.core.io.Resource;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Service orders: {@code /api/service_orders} for whoever performs them, and
 * {@code /api/clinical_encounters/{id}/service_orders} for the doctor ordering
 * them from the encounter.
 */
@RestController
@RequestMapping("/api")
public class ServiceOrderController {

    private final ServiceOrders service;

    public ServiceOrderController(ServiceOrders service) {
        this.service = service;
    }

    @GetMapping("/service_orders")
    @PreAuthorize("hasAuthority('" + Permissions.SERVICE_ORDERS_READ + "')")
    public ResponseEntity<List<ServiceOrderResponse>> list(@RequestParam MultiValueMap<String, String> query) {
        Page<ServiceOrder> page = service.list(new ListParams(query));
        return ListResponse.of(responses(page.getContent()), page.getTotalElements());
    }

    @GetMapping("/service_orders/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.SERVICE_ORDERS_READ + "')")
    public ServiceOrderResponse get(@PathVariable Long id) {
        return response(service.get(id));
    }

    @GetMapping("/clinical_encounters/{encounterId}/service_orders")
    @PreAuthorize("hasAuthority('" + Permissions.SERVICE_ORDERS_READ + "')")
    public List<ServiceOrderResponse> ofEncounter(@PathVariable Long encounterId) {
        return responses(service.forEncounter(encounterId));
    }

    @PostMapping("/clinical_encounters/{encounterId}/service_orders")
    @PreAuthorize("hasAuthority('" + Permissions.SERVICE_ORDERS_WRITE + "')")
    public ResponseEntity<ServiceOrderResponse> order(@PathVariable Long encounterId,
                                                      @Valid @RequestBody ServiceOrderRequests.Order request) {
        ServiceOrder created = service.order(encounterId, request);
        return ResponseEntity.created(URI.create("/api/service_orders/" + created.getId())).body(response(created));
    }

    @PostMapping("/service_orders/{id}/start")
    @PreAuthorize("hasAuthority('" + Permissions.SERVICE_ORDERS_WRITE + "')")
    public ServiceOrderResponse start(@PathVariable Long id) {
        return response(service.start(id));
    }

    @RequestMapping(value = "/service_orders/{id}/result", method = {RequestMethod.PATCH, RequestMethod.PUT})
    @PreAuthorize("hasAuthority('" + Permissions.SERVICE_ORDERS_WRITE + "')")
    public ServiceOrderResponse result(@PathVariable Long id, @Valid @RequestBody ServiceOrderRequests.Result request) {
        return response(service.recordResult(id, request));
    }

    @PostMapping(value = "/service_orders/{id}/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('" + Permissions.SERVICE_ORDERS_WRITE + "')")
    public ServiceOrderResponse attach(@PathVariable Long id, @RequestPart("file") MultipartFile file) {
        return response(service.attach(id, file));
    }

    /** Always a download, never shown inline: a file somebody uploaded does not run in this origin. */
    @GetMapping("/service_orders/{id}/files/{fileId}")
    @PreAuthorize("hasAuthority('" + Permissions.SERVICE_ORDERS_READ + "')")
    public ResponseEntity<Resource> download(@PathVariable Long id, @PathVariable Long fileId) {
        ServiceOrders.StoredFile file = service.file(id, fileId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .contentLength(file.sizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.name(), java.nio.charset.StandardCharsets.UTF_8)
                                .build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(file.content());
    }

    @PostMapping("/service_orders/{id}/review")
    @PreAuthorize("hasAuthority('" + Permissions.SERVICE_ORDERS_WRITE + "')")
    public ServiceOrderResponse review(@PathVariable Long id) {
        return response(service.review(id));
    }

    @PostMapping("/service_orders/{id}/cancel")
    @PreAuthorize("hasAuthority('" + Permissions.SERVICE_ORDERS_WRITE + "')")
    public ServiceOrderResponse cancel(@PathVariable Long id, @Valid @RequestBody ServiceOrderRequests.Cancel request) {
        return response(service.cancel(id, request));
    }

    private ServiceOrderResponse response(ServiceOrder order) {
        return responses(List.of(order)).getFirst();
    }

    private List<ServiceOrderResponse> responses(List<ServiceOrder> list) {
        Map<Long, String> names = service.namesFor(list);
        return list.stream().map(order -> ServiceOrderResponse.from(order, names, service.capabilities(order))).toList();
    }
}
