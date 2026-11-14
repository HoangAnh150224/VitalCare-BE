package com.vn.vitalcare.care.staff.controller;

import com.vn.vitalcare.care.staff.service.EmployeeService;
import com.vn.vitalcare.care.staff.service.StaffRoles;
import com.vn.vitalcare.entity.StaffMembership;
import com.vn.vitalcare.identity.rowlevel.service.RowLevelSecurity;
import com.vn.vitalcare.care.staff.dto.EmployeeResponse;
import com.vn.vitalcare.share.security.Permissions;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * SRS 3.18.9 Staff Membership Management and 3.18.10 Clinic-Level Role
 * Management: {@code /api/employees/{id}/memberships} and
 * {@code /api/employees/{id}/clinic_roles}, and {@code /api/clinic_roles},
 * what each clinic-level role allows.
 */
@RestController
@Validated
public class StaffMembershipController {

    public record MembershipResponse(Long id, UUID clinicId, String clinicName, String status, OffsetDateTime startedAt,
                                     OffsetDateTime endedAt, String endReason) {
        static MembershipResponse of(StaffMembership m) {
            return new MembershipResponse(m.getId(), m.getClinic().getClinicId(), m.getClinic().getClinicName(),
                    m.getStatus(), m.getStartedAt(), m.getEndedAt(), m.getEndReason());
        }
    }

    public record TransferRequest(@NotNull(message = "Pick a clinic") UUID clinicId,
                                  @jakarta.validation.constraints.NotBlank(message = "Vui lòng nhập lý do chuyển cơ sở.")
                                  @Size(max = 500, message = "Lý do không được vượt quá 500 ký tự.") String reason) {
    }

    public record EndRequest(@Size(max = 500, message = "At most 500 characters") String reason) {
    }

    public record RolesRequest(Set<String> roles) {
    }

    /** One clinic-level role and, per group of the matrix, ALLOWED, BY_PERMISSION or NONE. */
    public record ClinicRole(String code, String name, boolean adminOnly, Map<String, String> access) {
    }

    public record StaffRolesResponse(Long employeeId, List<String> roles) {
    }

    private final EmployeeService employees;
    private final RowLevelSecurity rowLevel;
    private final NamedParameterJdbcTemplate jdbc;

    public StaffMembershipController(EmployeeService employees, RowLevelSecurity rowLevel, NamedParameterJdbcTemplate jdbc) {
        this.employees = employees;
        this.rowLevel = rowLevel;
        this.jdbc = jdbc;
    }

    @GetMapping("/api/employees/{id}/memberships")
    @PreAuthorize("hasAuthority('" + Permissions.STAFF_MEMBERSHIPS_READ + "')")
    public List<MembershipResponse> memberships(@PathVariable Long id) {
        return employees.memberships(id).stream().map(MembershipResponse::of).toList();
    }

    @PostMapping("/api/employees/{id}/memberships/transfer")
    @PreAuthorize("hasAuthority('" + Permissions.STAFF_MEMBERSHIPS_TRANSFER + "')")
    public EmployeeResponse transfer(@PathVariable Long id, @org.springframework.web.bind.annotation.RequestBody
                                     @jakarta.validation.Valid TransferRequest request) {
        return EmployeeResponse.from(employees.transfer(id, request.clinicId(), request.reason()), rowLevel);
    }

    @PostMapping("/api/employees/{id}/memberships/end")
    @PreAuthorize("hasAuthority('" + Permissions.STAFF_MEMBERSHIPS_WRITE + "')")
    public EmployeeResponse end(@PathVariable Long id, @RequestBody @jakarta.validation.Valid EndRequest request) {
        return EmployeeResponse.from(employees.endMembership(id, request.reason()), rowLevel);
    }

    @GetMapping("/api/employees/{id}/clinic_roles")
    @PreAuthorize("hasAuthority('" + Permissions.CLINIC_ROLES_READ + "')")
    public StaffRolesResponse roles(@PathVariable Long id) {
        return new StaffRolesResponse(id, employees.clinicRoles(id));
    }

    @PutMapping("/api/employees/{id}/clinic_roles")
    @PreAuthorize("hasAuthority('" + Permissions.CLINIC_ROLES_WRITE + "')")
    public StaffRolesResponse setRoles(@PathVariable Long id, @RequestBody RolesRequest request) {
        employees.setClinicRoles(id, request.roles());
        return roles(id);
    }

    /** The clinic-level roles and the permission matrix the screen shows (SRS 3.18.10). */
    @GetMapping("/api/clinic_roles")
    @PreAuthorize("hasAuthority('" + Permissions.CLINIC_ROLES_READ + "')")
    public List<ClinicRole> clinicRoles() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select r.code as role, p.code as permission from roles r
                join role_permissions rp on rp.role_id = r.id join permissions p on p.id = rp.permission_id
                where r.code in (:codes)""", Map.of("codes", StaffRoles.CLINIC_LEVEL.keySet()));
        List<ClinicRole> out = new ArrayList<>();
        for (var role : StaffRoles.CLINIC_LEVEL.entrySet()) {
            Set<String> held = new java.util.HashSet<>();
            for (Map<String, Object> row : rows) {
                if (role.getKey().equals(row.get("role"))) {
                    held.add(String.valueOf(row.get("permission")));
                }
            }
            Map<String, String> access = new LinkedHashMap<>();
            StaffRoles.GROUPS.forEach((group, resources) -> {
                boolean writes = held.stream().anyMatch(code -> resources.contains(code.split(":")[0]) && !code.endsWith(":read"));
                boolean reads = held.stream().anyMatch(code -> resources.contains(code.split(":")[0]));
                access.put(group, writes ? "ALLOWED" : reads ? "BY_PERMISSION" : "NONE");
            });
            out.add(new ClinicRole(role.getKey(), role.getValue(), StaffRoles.ADMIN_ONLY.contains(role.getKey()), access));
        }
        return out;
    }
}
