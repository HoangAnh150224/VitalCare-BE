package com.vn.vitalcare.care.viewas.service.impl;

import com.vn.vitalcare.care.appointment.service.AppointmentService;
import com.vn.vitalcare.care.assignment.dto.MyPatientResponse;
import com.vn.vitalcare.care.assignment.service.MyPatientsService;
import com.vn.vitalcare.care.customer.service.CustomerService;
import com.vn.vitalcare.care.device.dto.DeviceResponse;
import com.vn.vitalcare.care.device.service.DeviceService;
import com.vn.vitalcare.care.staff.service.EmployeeService;
import com.vn.vitalcare.care.viewas.service.ViewAsService;
import com.vn.vitalcare.entity.Appointment;
import com.vn.vitalcare.entity.DeviceAssignment;
import com.vn.vitalcare.entity.MedicalDevice;
import com.vn.vitalcare.identity.rowlevel.service.RowLevelSecurity;
import com.vn.vitalcare.share.web.ListParams;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The {@link ViewAsService} the application runs on. */
@Service
@Transactional(readOnly = true)
public class ViewAsServiceImpl implements ViewAsService {

    private final CustomerService customerService;
    private final AppointmentService appointmentService;
    private final EmployeeService employeeService;
    private final MyPatientsService myPatientsService;
    private final DeviceService deviceService;
    private final RowLevelSecurity rowLevel;

    public ViewAsServiceImpl(CustomerService customerService,
                             AppointmentService appointmentService,
                             EmployeeService employeeService,
                             MyPatientsService myPatientsService,
                             DeviceService deviceService,
                             RowLevelSecurity rowLevel) {
        this.customerService = customerService;
        this.appointmentService = appointmentService;
        this.employeeService = employeeService;
        this.myPatientsService = myPatientsService;
        this.deviceService = deviceService;
        this.rowLevel = rowLevel;
    }

    @Override
    public Page<Appointment> customerAppointments(Long customerId, ListParams params) {
        return appointmentService.listForCustomer(customerService.get(customerId), params);
    }

    @Override
    public Page<Appointment> frontDeskAppointments(ListParams params) {
        return rowLevel.asSystem("view-as front desk appointments", () -> appointmentService.list(params));
    }

    @Override
    public List<DeviceResponse> frontDeskDevices(ListParams params, long[] total) {
        return rowLevel.asSystem("view-as front desk devices", () -> {
            Page<MedicalDevice> page = deviceService.list(params);
            total[0] = page.getTotalElements();
            Map<Long, DeviceAssignment> open = deviceService.openAssignments(
                    page.getContent().stream().map(MedicalDevice::getId).toList());
            // No row flags: a view is read-only whatever the scope would say.
            return page.getContent().stream()
                    .map(device -> DeviceResponse.from(device, open.get(device.getId()), null)).toList();
        });
    }

    @Override
    public Appointment customerAppointment(Long customerId, Long appointmentId) {
        return appointmentService.getOwn(customerService.get(customerId), appointmentId);
    }

    @Override
    public List<MyPatientResponse> employeePatients(Long employeeId) {
        return myPatientsService.list(employeeService.get(employeeId));
    }

    @Override
    public List<MyPatientResponse> allPatients(Long employeeId) {
        return myPatientsService.listAll(employeeId);
    }

    @Override
    public MyPatientResponse employeePatient(Long employeeId, Long customerId) {
        return myPatientsService.get(employeeService.get(employeeId), customerId);
    }
}
