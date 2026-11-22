package com.vn.vitalcare.care.monitoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.care.customer.service.CustomerService;
import com.vn.vitalcare.care.encounter.repository.ClinicalEncounterRepository;
import com.vn.vitalcare.care.staff.service.ClinicScope;
import com.vn.vitalcare.entity.Clinic;
import com.vn.vitalcare.entity.ClinicalEncounter;
import com.vn.vitalcare.entity.Customer;
import com.vn.vitalcare.entity.MonitoringOrder;
import com.vn.vitalcare.identity.rowlevel.service.RowLevelSecurity;
import com.vn.vitalcare.share.exception.ConflictException;
import com.vn.vitalcare.share.exception.FieldValidationException;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

/** BF-04 / FE-02 Assign Device to Patient: the monitoring order and the patient's consent. Report-5.1, sheet BE_Gaps. */
class MonitoringOrdersUnitTest {

    private static final UUID MY_CLINIC = UUID.fromString("5b1f8c2e-6a3d-4f7e-9c41-2d8e0a7b3c19");
    private static final UUID VISIT_CLINIC = UUID.fromString("0f9d2c1a-1111-4e7e-9c41-2d8e0a7b3c19");
    private MonitoringOrderRepository orders;
    private ClinicalEncounterRepository encounters;
    private ClinicScope scope;
    private RowLevelSecurity rowLevel;
    private ApplicationEventPublisher events;
    private MonitoringOrders service;
    private Customer patient;

    @BeforeEach
    void setUp() {
        orders = mock(MonitoringOrderRepository.class);
        encounters = mock(ClinicalEncounterRepository.class);
        scope = mock(ClinicScope.class);
        rowLevel = mock(RowLevelSecurity.class);
        events = mock(ApplicationEventPublisher.class);
        CustomerService customers = mock(CustomerService.class);
        when(rowLevel.byId(any(), any(), any())).thenReturn((root, query, cb) -> null);
        when(scope.currentClinicId()).thenReturn(Optional.of(MY_CLINIC));
        when(orders.persist(any(MonitoringOrder.class))).thenAnswer(i -> i.getArgument(0));
        patient = new Customer();
        ReflectionTestUtils.setField(patient, "id", 1L);
        when(customers.get(1L)).thenReturn(patient);
        service = new MonitoringOrders(orders, encounters, customers, scope, rowLevel, events,
                Clock.fixed(Instant.parse("2026-11-22T03:00:00Z"), ZoneOffset.UTC));
    }

    private static MonitoringOrders.OrderRequest request(Long encounterId, String decision, String note) {
        return new MonitoringOrders.OrderRequest(1L, encounterId, "  SpO₂ thấp về đêm  ", decision, note);
    }

    @Test void mo01_acceptedConsentStartsActiveMonitoringAtMyClinic() {
        MonitoringOrder o = service.create(request(null, " accepted ", "  Đã giải thích  "));
        assertEquals(MonitoringOrder.ACCEPTED, o.getDecision());
        assertEquals(MonitoringOrder.ACTIVE, o.getStatus());
        assertEquals("SpO₂ thấp về đêm", o.getReason());
        assertEquals("Đã giải thích", o.getConsentNote());
        assertEquals(MY_CLINIC, o.getClinicId());
        verify(rowLevel).checkWritable(o);
        verify(events).publishEvent(any(Object.class));
    }

    @Test void mo02_declinedIsRecordedNotActive() {
        MonitoringOrder o = service.create(request(null, "DECLINED", " "));
        assertEquals(MonitoringOrder.DECLINED, o.getStatus());
        assertNull(o.getConsentNote());
    }

    @Test void mo03_decisionOtherThanAcceptOrDeclineRefused() {
        assertEquals("The patient accepts or declines",
                assertThrows(FieldValidationException.class, () -> service.create(request(null, "MAYBE", null))).getMessage());
        verify(orders, never()).persist(any());
    }

    @Test void mo04_secondAcceptedOrderRefused_declineStillRecorded() {
        when(orders.hasActive(1L)).thenReturn(true);
        assertEquals("This patient is already under an accepted monitoring order",
                assertThrows(ConflictException.class, () -> service.create(request(null, "ACCEPTED", null))).getMessage());
        assertEquals(MonitoringOrder.DECLINED, service.create(request(null, "DECLINED", null)).getStatus());
    }

    @Test void mo05_fromAVisitTheClinicIsTheVisits() {
        ClinicalEncounter visit = new ClinicalEncounter();
        visit.setCustomer(patient);
        Clinic clinic = new Clinic();
        clinic.setClinicId(VISIT_CLINIC);
        visit.setClinic(clinic);
        when(encounters.findOne(any(Specification.class))).thenReturn(Optional.of(visit));
        assertEquals(VISIT_CLINIC, service.create(request(20L, "ACCEPTED", null)).getClinicId());
    }

    @Test void mo06_anotherPatientsVisitRefused() {
        ClinicalEncounter visit = new ClinicalEncounter();
        Customer other = new Customer();
        ReflectionTestUtils.setField(other, "id", 2L);
        visit.setCustomer(other);
        when(encounters.findOne(any(Specification.class))).thenReturn(Optional.of(visit));
        assertEquals("That visit is not this patient's",
                assertThrows(FieldValidationException.class, () -> service.create(request(20L, "ACCEPTED", null))).getMessage());
    }

    @Test void mo07_noVisitAndNoClinicOfMyOwn() {
        when(scope.currentClinicId()).thenReturn(Optional.empty());
        assertEquals("Order it from a visit",
                assertThrows(FieldValidationException.class, () -> service.create(request(null, "ACCEPTED", null))).getMessage());
    }

    @Test void mo08_endActiveMonitoring_onlyOnce() {
        MonitoringOrder active = new MonitoringOrder();
        active.setStatus(MonitoringOrder.ACTIVE);
        when(orders.findOne(any(Specification.class))).thenReturn(Optional.of(active));
        service.end(5L, "  Đã ổn định  ");
        assertEquals(MonitoringOrder.ENDED, active.getStatus());
        assertNotNull(ReflectionTestUtils.getField(active, "endedAt"));
        assertEquals("Only active monitoring can be ended",
                assertThrows(ConflictException.class, () -> service.end(5L, null)).getMessage());
    }

    @Test void mo09_unknownOrderIs404() {
        when(orders.findOne(any(Specification.class))).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> service.end(9L, null));
    }
}
