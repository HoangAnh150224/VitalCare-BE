package com.vn.vitalcare.care.staff.controller;

import com.vn.vitalcare.care.staff.dto.EmployeeCreateRequest;
import com.vn.vitalcare.care.staff.dto.EmployeePatchRequest;
import com.vn.vitalcare.care.staff.dto.EmployeeResponse;
import com.vn.vitalcare.care.staff.service.EmployeeService;
import com.vn.vitalcare.entity.Employee;
import com.vn.vitalcare.share.security.Permissions;
import com.vn.vitalcare.share.web.ListParams;
import com.vn.vitalcare.share.web.ListResponse;
import jakarta.validation.Valid;
import java.net.URI;
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
 * {@code /api/employees} — clinical staff.
 *
 * <p>No delete: somebody who leaves is set inactive, which keeps the record
 * their patients' history points at.
 */
@RestController
@RequestMapping("/api/employees")
public class EmployeeController {

    private final EmployeeService service;

    public EmployeeController(EmployeeService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.EMPLOYEES_READ + "')")
    public ResponseEntity<List<EmployeeResponse>> list(@RequestParam MultiValueMap<String, String> query) {
        ListParams params = new ListParams(query);

        List<Long> ids = params.ids();
        if (!ids.isEmpty()) {
            List<EmployeeResponse> rows = service.getMany(ids).stream().map(EmployeeResponse::from).toList();
            return ListResponse.of(rows, rows.size());
        }

        Page<Employee> page = service.list(params);
        return ListResponse.of(page.map(EmployeeResponse::from).getContent(), page.getTotalElements());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.EMPLOYEES_READ + "')")
    public EmployeeResponse get(@PathVariable Long id) {
        return EmployeeResponse.from(service.get(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + Permissions.EMPLOYEES_WRITE + "')")
    public ResponseEntity<EmployeeResponse> create(@Valid @RequestBody EmployeeCreateRequest request) {
        Employee created = service.create(request);
        return ResponseEntity
                .created(URI.create("/api/employees/" + created.getId()))
                .body(EmployeeResponse.from(created));
    }

    @RequestMapping(value = "/{id}", method = {RequestMethod.PATCH, RequestMethod.PUT})
    @PreAuthorize("hasAuthority('" + Permissions.EMPLOYEES_WRITE + "')")
    public EmployeeResponse update(@PathVariable Long id, @Valid @RequestBody EmployeePatchRequest request) {
        return EmployeeResponse.from(service.update(id, request));
    }
}
