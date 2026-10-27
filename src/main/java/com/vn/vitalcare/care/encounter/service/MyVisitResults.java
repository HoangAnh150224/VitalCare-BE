package com.vn.vitalcare.care.encounter.service;

import com.vn.vitalcare.care.appointment.service.AppointmentService;
import com.vn.vitalcare.care.customer.service.CustomerService;
import com.vn.vitalcare.care.encounter.dto.EncounterResponse;
import com.vn.vitalcare.care.encounter.dto.MyVisitResult;
import com.vn.vitalcare.care.encounter.repository.ClinicalEncounterRepository;
import com.vn.vitalcare.care.serviceorder.ServiceOrderRepository;
import com.vn.vitalcare.care.serviceorder.ServiceOrderResponse;
import com.vn.vitalcare.care.serviceorder.ServiceOrders;
import com.vn.vitalcare.entity.Appointment;
import com.vn.vitalcare.entity.AppointmentStatus;
import com.vn.vitalcare.entity.ClinicalEncounter;
import com.vn.vitalcare.entity.EncounterStatus;
import com.vn.vitalcare.entity.ServiceOrder;
import com.vn.vitalcare.entity.ServiceOrderFile;
import com.vn.vitalcare.entity.ServiceOrderStatus;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import com.vn.vitalcare.share.storage.FileStorage;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A customer's own visit result: the doctor's encounter, the services ordered
 * in it with their results, and the files those carry.
 *
 * <p>Pinned to the signed-in account the same way the appointment and the
 * invoice are: everything starts from {@link AppointmentService#getOwn}, which
 * admits the owner's visits and their relatives', and answers 404 for anyone
 * else's. The encounter and order reads below are unscoped on purpose — a
 * customer has no row-level scope — and are only ever reached past that check.
 *
 * <p>Nothing is shown until the visit is concluded (the user's decision): a
 * diagnosis or a lab value read half-way through a visit, before the doctor
 * has put it together, does a patient more harm than waiting.
 */
@Service
@Transactional(readOnly = true)
public class MyVisitResults {

    private final CustomerService customerService;
    private final AppointmentService appointmentService;
    private final ClinicalEncounterRepository encounters;
    private final ServiceOrderRepository orders;
    private final ServiceOrders serviceOrders;
    private final FileStorage storage;

    public MyVisitResults(CustomerService customerService,
                          AppointmentService appointmentService,
                          ClinicalEncounterRepository encounters,
                          ServiceOrderRepository orders,
                          ServiceOrders serviceOrders,
                          FileStorage storage) {
        this.customerService = customerService;
        this.appointmentService = appointmentService;
        this.encounters = encounters;
        this.orders = orders;
        this.serviceOrders = serviceOrders;
        this.storage = storage;
    }

    /** The concluded visit's encounter and its services, cancelled ones left out. */
    public MyVisitResult result(Long appointmentId) {
        ClinicalEncounter encounter = concludedEncounter(appointmentId);
        List<ServiceOrder> done = ordersOf(encounter);
        Map<Long, String> names = serviceOrders.namesFor(done);
        return new MyVisitResult(
                EncounterResponse.from(encounter, null),
                done.stream().map(order -> ServiceOrderResponse.from(order, names, Map.of())).toList());
    }

    /** A file attached to one of this visit's results; any other id is not found. */
    public ServiceOrders.StoredFile file(Long appointmentId, Long fileId) {
        ServiceOrderFile file = ordersOf(concludedEncounter(appointmentId)).stream()
                .flatMap(order -> order.getFiles().stream())
                .filter(candidate -> candidate.getId().equals(fileId) && candidate.getDeletedAt() == null)
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("File", fileId));
        return new ServiceOrders.StoredFile(file.getOriginalName(), file.getContentType(), file.getSizeBytes(),
                storage.load(file.getStorageKey()));
    }

    private ClinicalEncounter concludedEncounter(Long appointmentId) {
        Appointment appointment = appointmentService.getOwn(customerService.getForCurrentUser(), appointmentId);
        if (appointment.getStatus() != AppointmentStatus.COMPLETED) {
            throw notFound(appointmentId);
        }
        return encounters.findOfOwnAppointment(appointment.getId()).stream()
                .filter(encounter -> encounter.getStatus() == EncounterStatus.COMPLETED)
                .findFirst()
                .orElseThrow(() -> notFound(appointmentId));
    }

    private List<ServiceOrder> ordersOf(ClinicalEncounter encounter) {
        return orders.findByEncounter(encounter.getId()).stream()
                .filter(order -> order.getStatus() != ServiceOrderStatus.CANCELLED)
                .toList();
    }

    private static ResourceNotFoundException notFound(Long appointmentId) {
        return new ResourceNotFoundException("Result for appointment", appointmentId);
    }
}
