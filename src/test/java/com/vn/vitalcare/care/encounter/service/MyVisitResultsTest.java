package com.vn.vitalcare.care.encounter.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.care.appointment.service.AppointmentService;
import com.vn.vitalcare.care.customer.service.CustomerService;
import com.vn.vitalcare.care.encounter.dto.MyVisitResult;
import com.vn.vitalcare.care.encounter.repository.ClinicalEncounterRepository;
import com.vn.vitalcare.care.serviceorder.ServiceOrderRepository;
import com.vn.vitalcare.care.serviceorder.ServiceOrders;
import com.vn.vitalcare.entity.Appointment;
import com.vn.vitalcare.entity.AppointmentStatus;
import com.vn.vitalcare.entity.ClinicalEncounter;
import com.vn.vitalcare.entity.Customer;
import com.vn.vitalcare.entity.EncounterStatus;
import com.vn.vitalcare.entity.MedicalService;
import com.vn.vitalcare.entity.ServiceOrder;
import com.vn.vitalcare.entity.ServiceOrderFile;
import com.vn.vitalcare.entity.ServiceOrderStatus;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import com.vn.vitalcare.share.storage.FileStorage;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * A customer reads the result of their own concluded visit and nothing else:
 * not somebody else's, not one still under way, not a file from another visit.
 */
class MyVisitResultsTest {

    private static final long APPOINTMENT = 10L;

    private AppointmentService appointments;
    private ClinicalEncounterRepository encounters;
    private ServiceOrderRepository orders;
    private FileStorage storage;
    private MyVisitResults service;

    private Customer me;
    private Appointment appointment;
    private ClinicalEncounter encounter;

    @BeforeEach
    void setUp() {
        CustomerService customers = mock(CustomerService.class);
        appointments = mock(AppointmentService.class);
        encounters = mock(ClinicalEncounterRepository.class);
        orders = mock(ServiceOrderRepository.class);
        storage = mock(FileStorage.class);
        ServiceOrders serviceOrders = mock(ServiceOrders.class);
        when(serviceOrders.namesFor(any())).thenReturn(new java.util.HashMap<>());

        me = new Customer();
        ReflectionTestUtils.setField(me, "id", 1L);
        me.setFullName("Nguyễn Thị Lan");
        when(customers.getForCurrentUser()).thenReturn(me);

        appointment = new Appointment();
        ReflectionTestUtils.setField(appointment, "id", APPOINTMENT);
        ReflectionTestUtils.setField(appointment, "status", AppointmentStatus.COMPLETED);
        when(appointments.getOwn(me, APPOINTMENT)).thenReturn(appointment);

        encounter = new ClinicalEncounter();
        ReflectionTestUtils.setField(encounter, "id", 100L);
        encounter.setCustomer(me);
        encounter.setStatus(EncounterStatus.COMPLETED);
        ReflectionTestUtils.setField(encounter, "diagnosis", "Viêm họng cấp");
        ReflectionTestUtils.setField(encounter, "clinicalNote", "Theo dõi thêm");
        when(encounters.findOfOwnAppointment(APPOINTMENT)).thenReturn(List.of(encounter));

        service = new MyVisitResults(customers, appointments, encounters, orders, serviceOrders, storage);
    }

    private ServiceOrder order(long id, ServiceOrderStatus status) {
        ServiceOrder order = new ServiceOrder();
        ReflectionTestUtils.setField(order, "id", id);
        ReflectionTestUtils.setField(order, "status", status);
        ReflectionTestUtils.setField(order, "files", new java.util.ArrayList<ServiceOrderFile>());
        order.setEncounter(encounter);
        order.setCustomer(me);
        order.setUnitPrice(new BigDecimal("150000"));
        MedicalService medicalService = new MedicalService();
        ReflectionTestUtils.setField(medicalService, "id", 7L);
        order.setService(medicalService);
        return order;
    }

    private ServiceOrderFile file(ServiceOrder order, long id) {
        ServiceOrderFile file = new ServiceOrderFile(order, "ket-qua.pdf", "application/pdf", 3L,
                "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0");
        ReflectionTestUtils.setField(file, "id", id);
        order.getFiles().add(file);
        return file;
    }

    @Test
    @DisplayName("a concluded visit: the whole encounter, clinical note included, and its services bar the cancelled")
    void concludedVisit() {
        when(orders.findByEncounter(100L)).thenReturn(List.of(
                order(1, ServiceOrderStatus.REVIEWED), order(2, ServiceOrderStatus.CANCELLED)));

        MyVisitResult result = service.result(APPOINTMENT);

        assertEquals("Viêm họng cấp", result.encounter().diagnosis());
        assertEquals("Theo dõi thêm", result.encounter().clinicalNote());
        assertEquals(List.of(1L), result.orders().stream().map(order -> order.id()).toList());
    }

    @Test
    @DisplayName("somebody else's appointment is not found, and nothing about it is read")
    void othersAreNotFound() {
        when(appointments.getOwn(me, 99L)).thenThrow(new ResourceNotFoundException("Appointment", 99L));

        assertThrows(ResourceNotFoundException.class, () -> service.result(99L));
        assertThrows(ResourceNotFoundException.class, () -> service.file(99L, 1L));
        verify(encounters, never()).findOfOwnAppointment(eq(99L));
    }

    @Test
    @DisplayName("nothing before the visit is concluded: neither the appointment nor the encounter may be open")
    void notBeforeConcluded() {
        ReflectionTestUtils.setField(appointment, "status", AppointmentStatus.CHECKED_IN);
        assertThrows(ResourceNotFoundException.class, () -> service.result(APPOINTMENT));

        ReflectionTestUtils.setField(appointment, "status", AppointmentStatus.COMPLETED);
        encounter.setStatus(EncounterStatus.IN_PROGRESS);
        assertThrows(ResourceNotFoundException.class, () -> service.result(APPOINTMENT));
    }

    @Test
    @DisplayName("a file is served only if it belongs to one of this visit's results")
    void fileMustBelongToTheVisit() {
        ServiceOrder done = order(1, ServiceOrderStatus.REVIEWED);
        file(done, 5L);
        when(orders.findByEncounter(100L)).thenReturn(List.of(done));
        when(storage.load(any())).thenReturn(new ByteArrayResource(new byte[] {1, 2, 3}));

        assertEquals("ket-qua.pdf", service.file(APPOINTMENT, 5L).name());
        assertThrows(ResourceNotFoundException.class, () -> service.file(APPOINTMENT, 6L));
    }

    @Test
    @DisplayName("a removed file, or one on a cancelled service, is not served")
    void removedOrCancelledFilesAreNotServed() {
        ServiceOrder done = order(1, ServiceOrderStatus.REVIEWED);
        ReflectionTestUtils.setField(file(done, 5L), "deletedAt", java.time.Instant.now());
        ServiceOrder cancelled = order(2, ServiceOrderStatus.CANCELLED);
        file(cancelled, 7L);
        when(orders.findByEncounter(100L)).thenReturn(List.of(done, cancelled));

        assertThrows(ResourceNotFoundException.class, () -> service.file(APPOINTMENT, 5L));
        assertThrows(ResourceNotFoundException.class, () -> service.file(APPOINTMENT, 7L));
        verify(storage, never()).load(any());
    }
}
