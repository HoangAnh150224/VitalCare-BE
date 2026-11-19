package com.vn.vitalcare.care.encounter.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.care.ClinicRowLevel;
import com.vn.vitalcare.care.appointment.service.AppointmentService;
import com.vn.vitalcare.care.serviceorder.ServiceOrderRepository;
import com.vn.vitalcare.care.customer.service.CustomerService;
import com.vn.vitalcare.care.encounter.dto.EncounterRequest;
import com.vn.vitalcare.care.encounter.repository.ClinicalEncounterRepository;
import com.vn.vitalcare.care.encounter.service.impl.ClinicalEncounterServiceImpl;
import com.vn.vitalcare.care.staff.service.EmployeeService;
import com.vn.vitalcare.entity.Clinic;
import com.vn.vitalcare.entity.ClinicalEncounter;
import com.vn.vitalcare.entity.Customer;
import com.vn.vitalcare.entity.Employee;
import com.vn.vitalcare.entity.EncounterStatus;
import com.vn.vitalcare.entity.PatientActivationSource;
import com.vn.vitalcare.entity.ServiceOrder;
import com.vn.vitalcare.entity.VisitVitals;
import com.vn.vitalcare.share.security.Permissions;
import java.math.BigDecimal;
import com.vn.vitalcare.share.exception.ConflictException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

/** Recording a visit: whose it is, and the 24 hours after completion in which it can still change (BR-27). */
class ClinicalEncounterServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-13T03:00:00Z");
    private static final UUID CLINIC = UUID.fromString("5b1f8c2e-6a3d-4f7e-9c41-2d8e0a7b3c19");

    private ClinicalEncounterRepository repository;
    private ServiceOrderRepository serviceOrders;
    private AppointmentService appointments;
    private ClinicalEncounterService service;
    private Employee doctor;
    private Customer patient;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(ClinicalEncounterRepository.class);
        when(repository.persist(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Clinic clinic = new Clinic();
        clinic.setClinicId(CLINIC);
        doctor = new Employee();
        doctor.setClinic(clinic);
        patient = new Customer();
        patient.activateAsPatient(PatientActivationSource.CHECK_IN, 1L, null);

        EmployeeService employees = mock(EmployeeService.class);
        when(employees.getForCurrentUser()).thenReturn(doctor);
        CustomerService customers = mock(CustomerService.class);
        when(customers.get(7L)).thenReturn(patient);

        serviceOrders = mock(ServiceOrderRepository.class);
        signedInAs(Permissions.CLINICAL_ENCOUNTERS_WRITE, Permissions.CLINICAL_ENCOUNTERS_CONCLUDE);
        appointments = mock(AppointmentService.class);
        service = new ClinicalEncounterServiceImpl(repository, customers, employees, appointments,
                serviceOrders, new ClinicRowLevel(() -> CLINIC), Clock.fixed(NOW, ZoneOffset.UTC),
                mock(com.vn.vitalcare.care.appointment.service.VisitReturns.class),
                mock(com.vn.vitalcare.care.billing.invoice.Invoices.class));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    /** The permission codes the request carries, as the JWT converter would grant them. */
    private static void signedInAs(String... permissions) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "1", null, AuthorityUtils.createAuthorityList(permissions)));
    }

    private static EncounterRequest form(String diagnosis, String note) {
        return new EncounterRequest(null, null, "Đau ngực", null, null, note, diagnosis, null);
    }

    @Test
    @DisplayName("a new encounter is the signed-in clinician's, at their clinic, in progress from now")
    void createIsTheCliniciansAtTheirClinic() {
        ClinicalEncounter created = service.create(new EncounterRequest(7L, null, "  Đau ngực  ", null, null, " ", null, null));

        assertEquals(doctor, created.getEmployee());
        assertEquals(patient, created.getCustomer());
        assertEquals(CLINIC, created.getClinicId());
        assertEquals(EncounterStatus.IN_PROGRESS, created.getStatus());
        assertEquals("Đau ngực", created.getChiefComplaint());
        assertEquals(null, created.getClinicalNote());
        assertNotNull(created.getStartedAt());
    }

    /** A paid, checked-in visit of the patient at the doctor's clinic, ready to be examined. */
    private com.vn.vitalcare.entity.Appointment visit(long id) {
        org.springframework.test.util.ReflectionTestUtils.setField(patient, "id", 7L);
        com.vn.vitalcare.entity.Appointment visit = new com.vn.vitalcare.entity.Appointment();
        org.springframework.test.util.ReflectionTestUtils.setField(visit, "id", id);
        visit.setCustomer(patient);
        visit.setClinic(doctor.getClinic());
        visit.checkIn(null, null);
        visit.markPaid(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        when(appointments.getOwn(patient, id)).thenReturn(visit);
        return visit;
    }

    @Test
    @DisplayName("a visit gets its encounter once its row is locked")
    void createLocksTheVisitFirst() {
        com.vn.vitalcare.entity.Appointment visit = visit(50L);

        ClinicalEncounter created = service.create(new EncounterRequest(7L, 50L, "Ho", null, null, null, null, null));

        assertEquals(visit, created.getAppointment());
        verify(repository).lockAppointment(50L);
    }

    @Test
    @DisplayName("a visit that already has an encounter is not given a second one (two tabs at once)")
    void secondEncounterOfAVisitIsRefused() {
        visit(50L);
        when(repository.visitHasEncounter(50L)).thenReturn(true);

        ConflictException refused = assertThrows(ConflictException.class,
                () -> service.create(new EncounterRequest(7L, 50L, "Ho", null, null, null, null, null)));

        assertEquals("This visit already has an encounter", refused.getMessage());
        verify(repository, never()).persist(any());
    }

    @Test
    @DisplayName("BR-27: a completed encounter can be edited for 24 hours, then it is locked")
    @SuppressWarnings("unchecked")
    void lockedADayAfterCompletion() {
        ClinicalEncounter encounter = new ClinicalEncounter();
        encounter.setClinic(doctor.getClinic());
        encounter.setStatus(EncounterStatus.COMPLETED);
        when(repository.findOne(any(Specification.class))).thenReturn(Optional.of(encounter));
        EncounterRequest edit = form("Theo dõi thêm", null);

        encounter.setEndedAt(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).minusHours(23));
        assertEquals("Theo dõi thêm", service.update(1L, edit).getDiagnosis());

        encounter.setEndedAt(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).minusHours(25));
        assertThrows(ConflictException.class, () -> service.update(1L, edit));
    }

    @Test
    @DisplayName("completing an encounter stamps when it ended, and cannot be done twice")
    @SuppressWarnings("unchecked")
    void completeOnce() {
        ClinicalEncounter encounter = new ClinicalEncounter();
        encounter.setClinic(doctor.getClinic());
        encounter.setDiagnosis("Viêm họng cấp");
        when(repository.findOne(any(Specification.class))).thenReturn(Optional.of(encounter));

        assertEquals(EncounterStatus.COMPLETED, service.complete(1L).getStatus());
        assertEquals(NOW, encounter.getEndedAt().toInstant());
        assertEquals(doctor, encounter.getEmployee(), "the concluding doctor is the visit's clinician");
        assertThrows(ConflictException.class, () -> service.complete(1L));
    }

    @Test
    @DisplayName("FE-22: an encounter with an ordered service not yet reviewed cannot be concluded")
    @SuppressWarnings("unchecked")
    void completeWaitsForResults() {
        ClinicalEncounter encounter = new ClinicalEncounter();
        encounter.setClinic(doctor.getClinic());
        encounter.setDiagnosis("Viêm họng cấp");
        when(repository.findOne(any(Specification.class))).thenReturn(Optional.of(encounter));
        when(serviceOrders.findByEncounter(any())).thenReturn(List.of(new ServiceOrder()));

        assertThrows(ConflictException.class, () -> service.complete(1L));
        assertEquals(EncounterStatus.IN_PROGRESS, encounter.getStatus());
    }

    @Test
    @DisplayName("concluding is signing the visit off: not without a diagnosis")
    @SuppressWarnings("unchecked")
    void completeNeedsADiagnosis() {
        ClinicalEncounter encounter = new ClinicalEncounter();
        encounter.setClinic(doctor.getClinic());
        when(repository.findOne(any(Specification.class))).thenReturn(Optional.of(encounter));

        assertThrows(ConflictException.class, () -> service.complete(1L));
        assertEquals(EncounterStatus.IN_PROGRESS, encounter.getStatus());
    }

    @Test
    @DisplayName("a nurse writes the form but not the diagnosis: theirs is ignored, the doctor's kept, the rest saved")
    @SuppressWarnings("unchecked")
    void onlyTheDoctorWritesTheDiagnosis() {
        ClinicalEncounter encounter = new ClinicalEncounter();
        encounter.setClinic(doctor.getClinic());
        encounter.setDiagnosis("Viêm họng cấp");
        when(repository.findOne(any(Specification.class))).thenReturn(Optional.of(encounter));
        signedInAs(Permissions.CLINICAL_ENCOUNTERS_WRITE);

        assertEquals("Họng đỏ", service.update(1L, form("Viêm phổi", "Họng đỏ")).getClinicalNote());
        assertEquals("Viêm họng cấp", encounter.getDiagnosis());
        service.update(1L, form(null, "Họng đỏ"));
        assertEquals("Viêm họng cấp", encounter.getDiagnosis(), "a stale empty form does not clear it either");

        signedInAs(Permissions.CLINICAL_ENCOUNTERS_WRITE, Permissions.CLINICAL_ENCOUNTERS_CONCLUDE);
        assertEquals("Viêm phổi", service.update(1L, form("Viêm phổi", null)).getDiagnosis());
    }

    @Test
    @DisplayName("vitals are kept as measured, and none measured is stored as none")
    @SuppressWarnings("unchecked")
    void vitalsAsMeasured() {
        ClinicalEncounter encounter = new ClinicalEncounter();
        encounter.setClinic(doctor.getClinic());
        when(repository.findOne(any(Specification.class))).thenReturn(Optional.of(encounter));
        VisitVitals measured = new VisitVitals(new BigDecimal("38.5"), 96, 20, 120, 80, 97, null, null);

        assertEquals(measured, service.update(1L, new EncounterRequest(null, null, "Sốt", "Họng đỏ", measured,
                null, null, null)).getVitals());
        assertEquals("Họng đỏ", encounter.getExaminationFindings());
        VisitVitals none = new VisitVitals(null, null, null, null, null, null, null, null);
        assertEquals(null, service.update(1L, new EncounterRequest(null, null, "Sốt", null, none, null, null, null))
                .getVitals());
    }
}
