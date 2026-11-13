package com.vn.vitalcare.care.staff.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.vn.vitalcare.care.assignment.repository.MonitoringAssignmentRepository;
import com.vn.vitalcare.care.clinic.service.ClinicService;
import com.vn.vitalcare.care.staff.dto.EmployeeCreateRequest;
import com.vn.vitalcare.care.staff.repository.EmployeeRepository;
import com.vn.vitalcare.care.staff.repository.StaffMembershipRepository;
import com.vn.vitalcare.care.staff.service.impl.EmployeeServiceImpl;
import com.vn.vitalcare.entity.*;
import com.vn.vitalcare.identity.rowlevel.service.RowLevelSecurity;
import com.vn.vitalcare.identity.user.entity.User;
import com.vn.vitalcare.identity.user.service.UserService;
import com.vn.vitalcare.share.exception.ApiExceptionHandler;
import com.vn.vitalcare.share.exception.FieldValidationException;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

class EmployeeCreationTest {
    private final EmployeeRepository employees = mock(EmployeeRepository.class);
    private final UserService users = mock(UserService.class);
    private final ClinicService clinics = mock(ClinicService.class);
    private final StaffMembershipRepository memberships = mock(StaffMembershipRepository.class);
    private final RowLevelSecurity rowLevel = mock(RowLevelSecurity.class);
    private final EmployeeServiceImpl service = new EmployeeServiceImpl(employees,
            mock(MonitoringAssignmentRepository.class), memberships, users, clinics,
            mock(ClinicScope.class), rowLevel, mock(ApplicationEventPublisher.class), Clock.systemUTC());
    private final UUID clinicId = UUID.randomUUID();

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    private void signIn(String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("caller", "unused",
                        AuthorityUtils.createAuthorityList(authorities)));
    }

    private EmployeeCreateRequest request(StaffType type, UUID clinic) {
        return new EmployeeCreateRequest("0901234567", "Test Staff", "password123",
                type, null, null, null, null, clinic);
    }

    @ParameterizedTest
    @EnumSource(value = StaffType.class, names = {"DOCTOR", "RECEPTIONIST"})
    void systemAdminCannotCreateOtherStaffEvenWithClinicAdminRole(StaffType type) {
        signIn("ROLE_ADMIN", "ROLE_CLINIC_ADMIN", "employees:write", "clinics:write");
        var error = assertThrows(AuthorizationDeniedException.class,
                () -> service.create(request(type, clinicId)));
        assertEquals(HttpStatus.FORBIDDEN, new ApiExceptionHandler().handleAccessDenied(error).getStatusCode());
        verifyNoInteractions(users, employees, memberships, clinics);
    }

    @Test
    void clinicAdminRequiresExplicitClinic() {
        signIn("ROLE_ADMIN", "employees:write", "clinics:write");
        assertThrows(FieldValidationException.class,
                () -> service.create(request(StaffType.CLINIC_ADMIN, null)));
        verifyNoInteractions(users, employees, memberships, clinics);
    }

    @Test
    void cannotCreateClinicAdminWithoutClinicManagementPermission() {
        signIn("ROLE_CLINIC_ADMIN", "employees:write");
        assertThrows(AuthorizationDeniedException.class,
                () -> service.create(request(StaffType.CLINIC_ADMIN, clinicId)));
        verifyNoInteractions(users, employees, memberships, clinics);
    }

    @Test
    void missingClinicDoesNotCreateAnAccount() {
        signIn("ROLE_ADMIN", "employees:write", "clinics:write");
        when(clinics.get(clinicId)).thenThrow(new ResourceNotFoundException("Clinic", clinicId));
        assertThrows(ResourceNotFoundException.class,
                () -> service.create(request(StaffType.CLINIC_ADMIN, clinicId)));
        verifyNoInteractions(users, employees, memberships);
    }

    @Test
    void systemAdminCanCreateClinicAdmin() {
        signIn("ROLE_ADMIN", "employees:write", "clinics:write");
        assertCreates(StaffType.CLINIC_ADMIN);
    }

    @ParameterizedTest
    @EnumSource(value = StaffType.class, names = {"DOCTOR", "RECEPTIONIST"})
    void clinicAdminCanStillCreateClinicStaff(StaffType type) {
        signIn("ROLE_CLINIC_ADMIN", "employees:write");
        assertCreates(type);
    }

    private void assertCreates(StaffType type) {

        Clinic clinic = mock(Clinic.class);
        User user = mock(User.class);
        when(clinics.get(clinicId)).thenReturn(clinic);
        when(users.createStaffAccount("0901234567", "password123", "Test Staff", type.roleCode()))
                .thenReturn(user);
        when(employees.persist(any(Employee.class))).thenAnswer(invocation -> {
            Employee employee = invocation.getArgument(0);
            ReflectionTestUtils.setField(employee, "id", 42L);
            return employee;
        });
        Employee created = service.create(request(type, clinicId));
        assertEquals(type, created.getStaffType());
        assertSame(clinic, created.getClinic());
        assertSame(user, created.getUser());
        if (type == StaffType.CLINIC_ADMIN) {
            verify(rowLevel, never()).checkWritable(any());
        } else {
            verify(rowLevel).checkWritable(created);
        }
        verify(memberships).save(any(StaffMembership.class));
    }

    @Test
    void systemAdminMayGrantDoctorAndReceptionistWithoutEmployeeWrite() {
        signIn("ROLE_ADMIN", "clinic_roles:write");
        Employee employee = roleEmployee(java.util.Set.of("DOCTOR"));
        service.setClinicRoles(42L, java.util.Set.of("DOCTOR", "MANAGER"));
        verify(users).replaceRolesWithin(eq(7L), any(), eq(java.util.Set.of("DOCTOR", "MANAGER")));
    }

    @Test
    void lastClinicAdminCannotBeRemoved() {
        signIn("ROLE_ADMIN", "clinic_roles:write");
        roleEmployee(java.util.Set.of("CLINIC_ADMIN"));
        assertThrows(com.vn.vitalcare.share.exception.ConflictException.class,
                () -> service.setClinicRoles(42L, java.util.Set.of("DOCTOR")));
        verify(users, never()).replaceRolesWithin(any(), any(), any());
    }

    @Test
    void doctorCannotGrantRolesEvenWithWritePermission() {
        signIn("ROLE_DOCTOR", "employees:write", "clinic_roles:write");
        roleEmployee(java.util.Set.of("DOCTOR"));
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> service.setClinicRoles(42L, java.util.Set.of("DOCTOR", "MANAGER")));
        verify(users, never()).replaceRolesWithin(any(), any(), any());
    }

    private Employee roleEmployee(java.util.Set<String> roles) {
        Employee employee = new Employee();
        User user = mock(User.class);
        when(user.getId()).thenReturn(7L);
        when(user.roleCodes()).thenReturn(roles);
        employee.setUser(user);
        Clinic clinic = mock(Clinic.class);
        when(clinic.getClinicId()).thenReturn(clinicId);
        employee.setClinic(clinic);
        when(rowLevel.byId(Employee.class, com.vn.vitalcare.share.security.rowlevel.Action.WRITE, 42L))
                .thenReturn((root, query, cb) -> cb.conjunction());
        when(employees.findOne((org.springframework.data.jpa.domain.Specification<Employee>) any()))
                .thenReturn(java.util.Optional.of(employee));
        when(employees.lockRoleClinic(any())).thenReturn(java.util.Optional.of(clinic));
        return employee;
    }

    @Test
    void softDeleteRetainsEmployeeAndDisablesSignIn() {
        signIn("ROLE_ADMIN", "clinics:write");
        Employee employee = new Employee();
        ReflectionTestUtils.setField(employee, "id", 42L);
        employee.setStaffType(StaffType.CLINIC_ADMIN);
        employee.setStatus(EmployeeStatus.ACTIVE);
        User user = mock(User.class);
        when(user.getId()).thenReturn(7L);
        employee.setUser(user);
        when(employees.findForUpdate(42L)).thenReturn(java.util.Optional.of(employee));
        when(employees.exists((org.springframework.data.jpa.domain.Specification<Employee>) null)).thenReturn(true);
        when(employees.persist(employee)).thenReturn(employee);

        assertSame(employee, service.deleteClinicAdministrator(42L));
        assertTrue(employee.isDeleted());
        assertEquals(EmployeeStatus.INACTIVE, employee.getStatus());
        verify(users).setSignInAllowed(7L, false);
        verify(employees).persist(employee);
    }

    @Test
    void clinicStaffCannotDeleteClinicAdministrator() {
        signIn("ROLE_CLINIC_ADMIN", "employees:write");
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> service.deleteClinicAdministrator(42L));
        verifyNoInteractions(employees, users);
    }

    @Test
    void adminEditUpdatesLoginDetailsWithoutResettingPasswordWhenOmitted() {
        signIn("ROLE_ADMIN", "clinics:write");
        Employee employee = new Employee();
        employee.setStaffType(StaffType.CLINIC_ADMIN);
        User user = mock(User.class);
        when(user.getId()).thenReturn(7L);
        employee.setUser(user);
        when(employees.findOne((org.springframework.data.jpa.domain.Specification<Employee>) any()))
                .thenReturn(java.util.Optional.of(employee));
        when(rowLevel.byId(Employee.class, com.vn.vitalcare.share.security.rowlevel.Action.WRITE, 42L))
                .thenReturn((root, query, cb) -> cb.conjunction());
        when(employees.persist(employee)).thenReturn(employee);
        service.update(42L, new com.vn.vitalcare.care.staff.dto.EmployeePatchRequest(
                null, null, null, null, null, "0901234567", " Updated Name ", null, null));
        verify(users).update(7L, new com.vn.vitalcare.identity.user.dto.UserPatchRequest(
                "0901234567", null, "Updated Name", null, null));
        verify(users, never()).setPassword(any(), any());
    }
}
