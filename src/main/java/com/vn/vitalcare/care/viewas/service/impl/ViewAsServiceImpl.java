package com.vn.vitalcare.care.viewas.service.impl;

import com.vn.vitalcare.care.appointment.service.AppointmentService;
import com.vn.vitalcare.care.assignment.dto.MyPatientResponse;
import com.vn.vitalcare.care.assignment.service.MyPatientsService;
import com.vn.vitalcare.care.customer.service.CustomerService;
import com.vn.vitalcare.care.staff.service.EmployeeService;
import com.vn.vitalcare.care.viewas.service.ViewAsService;
import com.vn.vitalcare.entity.Appointment;
import com.vn.vitalcare.share.web.ListParams;
import java.util.List;
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

    public ViewAsServiceImpl(CustomerService customerService,
                             AppointmentService appointmentService,
                             EmployeeService employeeService,
                             MyPatientsService myPatientsService) {
        this.customerService = customerService;
        this.appointmentService = appointmentService;
        this.employeeService = employeeService;
        this.myPatientsService = myPatientsService;
    }

    @Override
    public Page<Appointment> customerAppointments(Long customerId, ListParams params) {
        return appointmentService.listForCustomer(customerService.get(customerId), params);
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
