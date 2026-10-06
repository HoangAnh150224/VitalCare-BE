package com.vn.vitalcare.care.assignment.controller;

import com.vn.vitalcare.care.assignment.dto.AssignmentRequests;
import com.vn.vitalcare.care.assignment.dto.CareTeamMemberResponse;
import com.vn.vitalcare.care.assignment.dto.DeviceAssignmentResponse;
import com.vn.vitalcare.care.assignment.service.CareAssignmentService;
import com.vn.vitalcare.share.security.Permissions;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/customers/{id}/care-team} and {@code /api/customers/{id}/devices}
 * — a patient's carers and the device they wear, current and past.
 *
 * <p>Reading follows the customer record ({@code customers:read}); changing
 * either is {@code customers:assign}. Ending is a POST to the assignment rather
 * than a DELETE, because nothing is removed.
 */
@RestController
@RequestMapping("/api/customers/{customerId}")
public class CustomerCareController {

    private final CareAssignmentService service;

    public CustomerCareController(CareAssignmentService service) {
        this.service = service;
    }

    @GetMapping("/care-team")
    @PreAuthorize("hasAuthority('" + Permissions.CUSTOMERS_READ + "')")
    public List<CareTeamMemberResponse> careTeam(@PathVariable Long customerId) {
        return service.careTeam(customerId).stream().map(CareTeamMemberResponse::from).toList();
    }

    @PostMapping("/care-team")
    @PreAuthorize("hasAuthority('" + Permissions.CUSTOMERS_ASSIGN + "')")
    public CareTeamMemberResponse assignStaff(@PathVariable Long customerId,
                                              @Valid @RequestBody AssignmentRequests.AssignStaff request) {
        return CareTeamMemberResponse.from(service.assignStaff(customerId, request));
    }

    @PostMapping("/care-team/{assignmentId}/end")
    @PreAuthorize("hasAuthority('" + Permissions.CUSTOMERS_ASSIGN + "')")
    public CareTeamMemberResponse endStaff(@PathVariable Long customerId,
                                           @PathVariable Long assignmentId,
                                           @Valid @RequestBody(required = false) AssignmentRequests.End request) {
        return CareTeamMemberResponse.from(service.endStaff(customerId, assignmentId, request));
    }

    @GetMapping("/devices")
    @PreAuthorize("hasAuthority('" + Permissions.CUSTOMERS_READ + "')")
    public List<DeviceAssignmentResponse> devices(@PathVariable Long customerId) {
        return service.devices(customerId).stream().map(DeviceAssignmentResponse::ofPatient).toList();
    }

    @PostMapping("/devices")
    @PreAuthorize("hasAuthority('" + Permissions.CUSTOMERS_ASSIGN + "')")
    public DeviceAssignmentResponse assignDevice(@PathVariable Long customerId,
                                                 @Valid @RequestBody AssignmentRequests.AssignDevice request) {
        return DeviceAssignmentResponse.ofPatient(service.assignDevice(customerId, request));
    }

    @PostMapping("/devices/{assignmentId}/return")
    @PreAuthorize("hasAuthority('" + Permissions.CUSTOMERS_ASSIGN + "')")
    public DeviceAssignmentResponse returnDevice(@PathVariable Long customerId,
                                                 @PathVariable Long assignmentId,
                                                 @Valid @RequestBody(required = false) AssignmentRequests.End request) {
        return DeviceAssignmentResponse.ofPatient(service.returnDevice(customerId, assignmentId, request));
    }
}
