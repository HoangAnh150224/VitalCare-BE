package com.vn.vitalcare.care.customer.controller;

import com.vn.vitalcare.care.customer.dto.CustomerPatchRequest;
import com.vn.vitalcare.care.customer.dto.CustomerResponse;
import com.vn.vitalcare.care.customer.service.CustomerService;
import com.vn.vitalcare.entity.Customer;
import com.vn.vitalcare.share.security.Permissions;
import com.vn.vitalcare.share.web.ListParams;
import com.vn.vitalcare.share.web.ListResponse;
import jakarta.validation.Valid;
import java.util.List;
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

/**
 * {@code /api/customers} — the clinic's view of everybody who registered,
 * neutral and patient alike.
 *
 * <p>No create and no delete. Customers come into being by registering, and a
 * record with clinical history behind it is not something to remove.
 */
@RestController
@RequestMapping("/api/customers")
public class CustomerController {

    private final CustomerService service;

    public CustomerController(CustomerService service) {
        this.service = service;
    }

    /** Serves both {@code getList} and {@code getMany}, as {@code UserController} does. */
    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.CUSTOMERS_READ + "')")
    public ResponseEntity<List<CustomerResponse>> list(@RequestParam MultiValueMap<String, String> query) {
        ListParams params = new ListParams(query);

        List<Long> ids = params.ids();
        if (!ids.isEmpty()) {
            List<CustomerResponse> rows = service.getMany(ids).stream()
                    .map(CustomerResponse::from)
                    .toList();
            return ListResponse.of(rows, rows.size());
        }

        Page<Customer> page = service.list(params);
        return ListResponse.of(page.map(CustomerResponse::from).getContent(), page.getTotalElements());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.CUSTOMERS_READ + "')")
    public CustomerResponse get(@PathVariable Long id) {
        return CustomerResponse.from(service.get(id));
    }

    @RequestMapping(value = "/{id}", method = {RequestMethod.PATCH, RequestMethod.PUT})
    @PreAuthorize("hasAuthority('" + Permissions.CUSTOMERS_WRITE + "')")
    public CustomerResponse update(@PathVariable Long id, @Valid @RequestBody CustomerPatchRequest request) {
        return CustomerResponse.from(service.update(id, request));
    }

    /** Makes a neutral customer a patient without a check-in. */
    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAuthority('" + Permissions.CUSTOMERS_ACTIVATE + "')")
    public CustomerResponse activate(@PathVariable Long id) {
        return CustomerResponse.from(service.activateManually(id));
    }
}
