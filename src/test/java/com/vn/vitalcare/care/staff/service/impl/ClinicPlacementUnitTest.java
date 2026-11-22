package com.vn.vitalcare.care.staff.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.care.staff.repository.EmployeeRepository;
import com.vn.vitalcare.entity.Clinic;
import com.vn.vitalcare.entity.Employee;
import com.vn.vitalcare.identity.rowlevel.service.RowLevelPrincipalResolver;
import com.vn.vitalcare.share.security.rowlevel.RowLevelPrincipal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** FE-17 Staff Membership: which clinic a signed-in member of staff works at. Report-5.1, sheet BE_Gaps. */
class ClinicPlacementUnitTest {

    private static final UUID CLINIC = UUID.fromString("5b1f8c2e-6a3d-4f7e-9c41-2d8e0a7b3c19");

    @Test void cp01_currentClinicIsThePrincipals_noneWhenSignedOut() {
        RowLevelPrincipalResolver principals = mock(RowLevelPrincipalResolver.class);
        RowLevelPrincipal me = mock(RowLevelPrincipal.class);
        when(me.clinicId()).thenReturn(CLINIC);
        when(principals.current()).thenReturn(Optional.of(me));
        assertEquals(Optional.of(CLINIC), new ClinicScopeImpl(principals).currentClinicId());
        when(principals.current()).thenReturn(Optional.empty());
        assertEquals(Optional.empty(), new ClinicScopeImpl(principals).currentClinicId());
    }

    @Test void cp02_staffAccountIsPlacedAtItsClinic_anyoneElseNowhere() {
        EmployeeRepository employees = mock(EmployeeRepository.class);
        Employee doctor = new Employee();
        Clinic clinic = new Clinic();
        clinic.setClinicId(CLINIC);
        doctor.setClinic(clinic);
        when(employees.findByUserIdAndDeletedAtIsNull(9L)).thenReturn(Optional.of(doctor));
        when(employees.findByUserIdAndDeletedAtIsNull(42L)).thenReturn(Optional.empty());
        StaffClinicPlacement placement = new StaffClinicPlacement(employees);
        assertEquals(Optional.of(CLINIC), placement.clinicOf(9L));
        assertEquals(Optional.empty(), placement.clinicOf(42L));
    }
}
