package com.vn.vitalcare.care.customer.controller;

import com.vn.vitalcare.identity.user.service.UserService;
import com.vn.vitalcare.identity.user.entity.User;
import com.vn.vitalcare.care.customer.dto.ActivityStatusRequest;
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
    private final UserService users;

    public CustomerController(CustomerService service, UserService users) {
        this.service = service;
        this.users = users;
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
        return withChanger(service.get(id));
    }

    /** SRS 3.8.5: whether the clinic is still looking after this person. */
    @PostMapping("/{id}/activity-status")
    @PreAuthorize("hasAuthority('" + Permissions.CUSTOMERS_STATUS + "')")
    public CustomerResponse changeActivityStatus(@PathVariable Long id,
                                                 @Valid @RequestBody ActivityStatusRequest request) {
        return withChanger(service.changeActivityStatus(id, request));
    }

    /** The customer with the name of whoever last changed their activity status. */
    private CustomerResponse withChanger(Customer customer) {
        Long changer = customer.getActivityChangedById();
        String name = changer == null ? null
                : users.getMany(List.of(changer)).stream().findFirst().map(User::getFullName).orElse(null);
        return CustomerResponse.from(customer, name);
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
