package com.vn.vitalcare.care.assignment.controller;

import com.vn.vitalcare.care.assignment.dto.AssignmentResponse;
import com.vn.vitalcare.care.assignment.service.CareAssignmentService;
import com.vn.vitalcare.entity.MonitoringAssignment;
import com.vn.vitalcare.identity.user.entity.User;
import com.vn.vitalcare.identity.user.service.UserService;
import com.vn.vitalcare.share.security.Permissions;
import com.vn.vitalcare.share.web.ListParams;
import com.vn.vitalcare.share.web.ListResponse;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/monitoring_assignments} — who follows whom across the clinic,
 * now and before. Read-only: an assignment is made and ended on the patient's
 * own page ({@code /customers/{id}/care-team}).
 */
@RestController
@RequestMapping("/api/monitoring_assignments")
public class MonitoringAssignmentController {

    private final CareAssignmentService service;
    private final UserService users;

    public MonitoringAssignmentController(CareAssignmentService service, UserService users) {
        this.service = service;
        this.users = users;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.MONITORING_ASSIGNMENTS_READ + "')")
    public ResponseEntity<List<AssignmentResponse>> list(@RequestParam MultiValueMap<String, String> query) {
        Page<MonitoringAssignment> page = service.assignments(new ListParams(query));
        return ListResponse.of(
                page.map(row -> AssignmentResponse.from(row, Map.of())).getContent(), page.getTotalElements());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.MONITORING_ASSIGNMENTS_READ + "')")
    public AssignmentResponse get(@PathVariable Long id) {
        MonitoringAssignment assignment = service.assignment(id);
        // Just the two names: the reader may not hold users:read, and needs no more of the accounts.
        List<Long> actors = Stream.of(assignment.getAssignedById(), assignment.getEndedById())
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, String> names = users.getMany(actors).stream()
                .collect(Collectors.toMap(User::getId, User::getFullName));
        return AssignmentResponse.from(assignment, names);
    }
}
