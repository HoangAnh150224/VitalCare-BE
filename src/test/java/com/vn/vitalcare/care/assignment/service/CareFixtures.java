package com.vn.vitalcare.care.assignment.service;

import com.vn.vitalcare.entity.Customer;
import com.vn.vitalcare.entity.DeviceStatus;
import com.vn.vitalcare.entity.Employee;
import com.vn.vitalcare.entity.EmployeeStatus;
import com.vn.vitalcare.entity.MedicalDevice;
import com.vn.vitalcare.entity.PatientActivationSource;
import com.vn.vitalcare.entity.StaffType;
import com.vn.vitalcare.identity.user.entity.User;
import com.vn.vitalcare.identity.user.entity.UserStatus;
import org.springframework.test.util.ReflectionTestUtils;

/** Records with ids, as the repositories would hand them back. */
final class CareFixtures {

    private CareFixtures() {
    }

    static User user(long id, String phone, String name) {
        User user = new User(phone, null, "hash", name, UserStatus.ACTIVE);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    static Customer patient(long id) {
        Customer customer = neutral(id);
        customer.activateAsPatient(PatientActivationSource.CHECK_IN, 1L, null);
        return customer;
    }

    static Customer neutral(long id) {
        Customer customer = new Customer();
        ReflectionTestUtils.setField(customer, "id", id);
        customer.setCustomerCode("KH%06d".formatted(id));
        customer.setUser(user(100 + id, "+8491234%04d".formatted(id), "Patient " + id));
        return customer;
    }

    static Employee staff(long id, EmployeeStatus status) {
        Employee employee = new Employee();
        ReflectionTestUtils.setField(employee, "id", id);
        employee.setUser(user(200 + id, "+8490000%04d".formatted(id), "Staff " + id));
        employee.setEmployeeCode("NV%06d".formatted(id));
        employee.setStaffType(StaffType.DOCTOR);
        employee.setStatus(status);
        return employee;
    }

    static MedicalDevice device(long id, DeviceStatus status) {
        MedicalDevice device = new MedicalDevice();
        ReflectionTestUtils.setField(device, "id", id);
        device.setDeviceCode("VC-W-%03d".formatted(id));
        device.setStatus(status);
        return device;
    }
}
