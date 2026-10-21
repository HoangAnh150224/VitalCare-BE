package com.vn.vitalcare.care.encounter.service.impl;

import com.vn.vitalcare.care.appointment.service.AppointmentService;
import com.vn.vitalcare.care.customer.service.CustomerService;
import com.vn.vitalcare.care.encounter.dto.EncounterRequest;
import com.vn.vitalcare.care.encounter.repository.ClinicalEncounterRepository;
import com.vn.vitalcare.care.encounter.repository.ClinicalEncounterSpecifications;
import com.vn.vitalcare.care.encounter.service.ClinicalEncounterService;
import com.vn.vitalcare.care.staff.service.EmployeeService;
import com.vn.vitalcare.entity.Appointment;
import com.vn.vitalcare.entity.AppointmentStatus;
import com.vn.vitalcare.entity.ClinicalEncounter;
import com.vn.vitalcare.entity.Customer;
import com.vn.vitalcare.entity.CustomerStatus;
import com.vn.vitalcare.entity.Employee;
import com.vn.vitalcare.entity.EncounterStatus;
import com.vn.vitalcare.identity.rowlevel.service.RowLevelSecurity;
import com.vn.vitalcare.share.data.BaseEntitySpecifications;
import com.vn.vitalcare.share.exception.ConflictException;
import com.vn.vitalcare.share.exception.FieldValidationException;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import com.vn.vitalcare.share.security.rowlevel.Action;
import com.vn.vitalcare.share.web.ListParams;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The {@link ClinicalEncounterService} the application runs on. */
@Service
@Transactional(readOnly = true)
public class ClinicalEncounterServiceImpl implements ClinicalEncounterService {

    private static final Set<String> SORTABLE = Set.of("id", "startedAt", "endedAt", "status");

    private static final Sort DEFAULT_SORT = Sort.by(Sort.Order.desc("startedAt"), Sort.Order.desc("id"));

    private final ClinicalEncounterRepository repository;
    private final CustomerService customerService;
    private final EmployeeService employeeService;
    private final AppointmentService appointmentService;
    private final RowLevelSecurity rowLevel;
    private final Clock clock;

    public ClinicalEncounterServiceImpl(ClinicalEncounterRepository repository,
                                        CustomerService customerService,
                                        EmployeeService employeeService,
                                        AppointmentService appointmentService,
                                        RowLevelSecurity rowLevel,
                                        Clock clock) {
        this.repository = repository;
        this.customerService = customerService;
        this.employeeService = employeeService;
        this.appointmentService = appointmentService;
        this.rowLevel = rowLevel;
        this.clock = clock;
    }

    @Override
    public Page<ClinicalEncounter> list(ListParams params) {
        return repository.findAll(
                rowLevel.scope(ClinicalEncounter.class, Action.READ).and(ClinicalEncounterSpecifications.from(params)),
                params.pageable(SORTABLE, DEFAULT_SORT));
    }

    @Override
    public List<ClinicalEncounter> getMany(List<Long> ids) {
        return repository.findAll(Specification.allOf(
                rowLevel.byIds(ClinicalEncounter.class, Action.READ, ids), BaseEntitySpecifications.notDeleted()));
    }

    @Override
    public ClinicalEncounter get(Long id) {
        return find(id, Action.READ);
    }

    @Override
    @Transactional
    public ClinicalEncounter create(EncounterRequest request) {
        if (request.customerId() == null) {
            throw new FieldValidationException("customerId", "A patient is required");
        }
        Employee clinician = employeeService.getForCurrentUser();
        if (clinician.getClinic() == null) {
            throw new ConflictException("Your staff record is not tied to a clinic");
        }
        // Through the clinic scope customers already have: another clinic's
        // patient answers as not found.
        Customer patient = customerService.get(request.customerId());
        if (patient.getStatus() != CustomerStatus.PATIENT) {
            throw new ConflictException("Only a patient can have an encounter; activate them first");
        }

        ClinicalEncounter encounter = new ClinicalEncounter();
        encounter.setCustomer(patient);
        encounter.setEmployee(clinician);
        encounter.setClinic(clinician.getClinic());
        encounter.setStartedAt(OffsetDateTime.now(clock));
        if (request.appointmentId() != null) {
            encounter.setAppointment(appointmentOf(patient, clinician, request.appointmentId()));
        }
        apply(encounter, request);

        ClinicalEncounter saved = repository.persist(encounter);
        // WITH CHECK against the complete row, after the flush; see DeviceServiceImpl.register.
        repository.flush();
        rowLevel.checkWritable(saved);
        return saved;
    }

    @Override
    @Transactional
    public ClinicalEncounter update(Long id, EncounterRequest request) {
        ClinicalEncounter encounter = find(id, Action.WRITE);
        // A completed row with no end time cannot say when its window closed: locked.
        if (encounter.getStatus() == EncounterStatus.COMPLETED
                && (encounter.getEndedAt() == null
                        || OffsetDateTime.now(clock).isAfter(encounter.getEndedAt().plus(EDIT_WINDOW)))) {
            // ponytail: locked for good. BR-27's signed addenda are a separate
            // append-only record; add them when somebody has to amend a locked visit.
            throw new ConflictException("This encounter was completed more than 24 hours ago and is locked");
        }
        apply(encounter, request);
        rowLevel.checkWritable(encounter);
        return repository.persist(encounter);
    }

    @Override
    @Transactional
    public ClinicalEncounter complete(Long id) {
        ClinicalEncounter encounter = find(id, Action.WRITE);
        if (encounter.getStatus() == EncounterStatus.COMPLETED) {
            throw new ConflictException("This encounter is already completed");
        }
        encounter.setStatus(EncounterStatus.COMPLETED);
        encounter.setEndedAt(OffsetDateTime.now(clock));
        rowLevel.checkWritable(encounter);
        // The visit this encounter documents is over too.
        if (encounter.getAppointment() != null) {
            appointmentService.completeForEncounter(encounter.getAppointment().getId());
        }
        return repository.persist(encounter);
    }

    /** The patient's own appointment, at the clinician's clinic; anything else is not this visit's. */
    private Appointment appointmentOf(Customer patient, Employee clinician, Long appointmentId) {
        Appointment appointment;
        try {
            appointment = appointmentService.getOwn(patient, appointmentId);
        } catch (ResourceNotFoundException e) {
            throw new FieldValidationException("appointmentId", "That appointment is not this patient's");
        }
        // getOwn reaches a whole account; the visit has to be this very person's.
        if (!patient.getId().equals(appointment.getCustomer().getId())) {
            throw new FieldValidationException("appointmentId", "That appointment is not this patient's");
        }
        if (!clinician.getClinic().getClinicId().equals(appointment.getClinicId())) {
            throw new FieldValidationException("appointmentId", "That appointment is at another clinic");
        }
        // Waiting for the doctor or already called into the room: either way, here.
        if (appointment.getStatus() != AppointmentStatus.CHECKED_IN
                && appointment.getStatus() != AppointmentStatus.IN_EXAM) {
            throw new FieldValidationException("appointmentId", "The patient has not checked in for that appointment");
        }
        return appointment;
    }

    private static void apply(ClinicalEncounter encounter, EncounterRequest request) {
        encounter.setChiefComplaint(request.chiefComplaint().trim());
        encounter.setClinicalNote(blankToNull(request.clinicalNote()));
        encounter.setDiagnosis(blankToNull(request.diagnosis()));
        encounter.setFollowUp(blankToNull(request.followUp()));
    }

    /** One row through the caller's scope for {@code action}; outside it is a 404. See DeviceServiceImpl. */
    private ClinicalEncounter find(Long id, Action action) {
        return repository.findOne(Specification.allOf(
                        rowLevel.byId(ClinicalEncounter.class, action, id), BaseEntitySpecifications.notDeleted()))
                .orElseThrow(() -> new ResourceNotFoundException("Clinical encounter", id));
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
