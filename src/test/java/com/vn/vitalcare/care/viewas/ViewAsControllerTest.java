package com.vn.vitalcare.care.viewas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.care.appointment.dto.AppointmentResponse;
import com.vn.vitalcare.care.appointment.service.AppointmentService;
import com.vn.vitalcare.care.assignment.dto.MyPatientResponse;
import com.vn.vitalcare.care.assignment.repository.DeviceAssignmentRepository;
import com.vn.vitalcare.care.assignment.repository.MonitoringAssignmentRepository;
import com.vn.vitalcare.care.assignment.service.MyPatientsService;
import com.vn.vitalcare.care.assignment.service.impl.MyPatientsServiceImpl;
import com.vn.vitalcare.care.customer.service.CustomerService;
import com.vn.vitalcare.care.staff.service.EmployeeService;
import com.vn.vitalcare.care.viewas.service.impl.ViewAsServiceImpl;
import com.vn.vitalcare.entity.Appointment;
import com.vn.vitalcare.entity.Clinic;
import com.vn.vitalcare.entity.Customer;
import com.vn.vitalcare.entity.Employee;
import com.vn.vitalcare.entity.EmployeeStatus;
import com.vn.vitalcare.entity.MonitoringAssignment;
import com.vn.vitalcare.entity.StaffType;
import com.vn.vitalcare.identity.user.entity.User;
import com.vn.vitalcare.identity.user.entity.UserStatus;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import com.vn.vitalcare.share.web.ListParams;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.LinkedMultiValueMap;

/** An administrator sees a customer's or a member of staff's own screens, and nothing more. */
class ViewAsControllerTest {

    private CustomerService customers;
    private AppointmentService appointments;
    private EmployeeService employees;
    private MonitoringAssignmentRepository careTeams;
    private ViewAsController controller;

    @BeforeEach
    void setUp() {
        customers = mock(CustomerService.class);
        appointments = mock(AppointmentService.class);
        employees = mock(EmployeeService.class);
        careTeams = mock(MonitoringAssignmentRepository.class);
        MyPatientsService myPatients =
                new MyPatientsServiceImpl(employees, careTeams, mock(DeviceAssignmentRepository.class));
        controller = new ViewAsController(new ViewAsServiceImpl(customers, appointments, employees, myPatients));
    }

    @Test
    @DisplayName("a customer's appointments come back as the customer sees them, without the internal note")
    void customerAppointmentsHideTheNote() {
        Customer customer = customer(1);
        Appointment appointment = appointment(10, customer);
        when(customers.get(1L)).thenReturn(customer);
        when(appointments.listForCustomer(any(), any(ListParams.class)))
                .thenReturn(new PageImpl<>(List.of(appointment)));
        when(appointments.getOwn(customer, 10L)).thenReturn(appointment);

        List<AppointmentResponse> rows =
                controller.customerAppointments(1L, new LinkedMultiValueMap<>()).getBody();
        assertEquals(1, rows.size());
        assertNull(rows.getFirst().note());
        verify(appointments).listForCustomer(eq(customer), any(ListParams.class));

        assertNull(controller.customerAppointment(1L, 10L).note());
    }

    @Test
    @DisplayName("another customer's appointment is not found while viewing as this one")
    void otherCustomersAppointmentIsNotFound() {
        Customer customer = customer(1);
        when(customers.get(1L)).thenReturn(customer);
        when(appointments.getOwn(customer, 99L)).thenThrow(new ResourceNotFoundException("Appointment", 99L));

        assertThrows(ResourceNotFoundException.class, () -> controller.customerAppointment(1L, 99L));
    }

    @Test
    @DisplayName("a member of staff's patients are theirs, not the administrator's, and others' patients are not found")
    void employeePatientsArePinnedToThatEmployee() {
        Employee doctor = staff(5);
        when(employees.get(5L)).thenReturn(doctor);
        MonitoringAssignment assignment = new MonitoringAssignment();
        assignment.start(customer(1), doctor, 1L, null);
        when(careTeams.findByEmployeeIdAndUnassignedAtIsNullAndDeletedAtIsNullOrderByAssignedAtDesc(5L))
                .thenReturn(List.of(assignment));

        List<MyPatientResponse> rows = controller.employeePatients(5L).getBody();
        assertEquals(1, rows.size());
        assertEquals(1L, rows.getFirst().id());
        assertEquals(1L, controller.employeePatient(5L, 1L).id());
        assertThrows(ResourceNotFoundException.class, () -> controller.employeePatient(5L, 2L));
        // Never the caller's own employee record: the administrator has none.
        verify(employees, never()).getForCurrentUser();
    }

    private static Customer customer(long id) {
        Customer customer = new Customer();
        ReflectionTestUtils.setField(customer, "id", id);
        customer.setCustomerCode("KH%06d".formatted(id));
        customer.setUser(user(100 + id, "Patient " + id));
        return customer;
    }

    private static Employee staff(long id) {
        Employee employee = new Employee();
        ReflectionTestUtils.setField(employee, "id", id);
        employee.setUser(user(200 + id, "Staff " + id));
        employee.setEmployeeCode("NV%06d".formatted(id));
        employee.setStaffType(StaffType.DOCTOR);
        employee.setStatus(EmployeeStatus.ACTIVE);
        return employee;
    }

    private static User user(long id, String name) {
        User user = new User("+849%08d".formatted(id), null, "hash", name, UserStatus.ACTIVE);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private static Appointment appointment(long id, Customer customer) {
        Clinic clinic = new Clinic();
        clinic.setClinicId(UUID.randomUUID());
        clinic.setClinicName("Phòng khám A");
        Appointment appointment = new Appointment();
        ReflectionTestUtils.setField(appointment, "id", id);
        appointment.setCustomer(customer);
        appointment.setClinic(clinic);
        appointment.setAppointmentDate(LocalDate.of(2026, 10, 7));
        appointment.setStartTime(LocalTime.of(8, 0));
        appointment.setNote("Ghi chú nội bộ của lễ tân");
        return appointment;
    }
}
