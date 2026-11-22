package com.vn.vitalcare.care.serviceorder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.care.billing.catalog.MedicalServiceCatalog;
import com.vn.vitalcare.care.billing.invoice.Invoices;
import com.vn.vitalcare.care.encounter.repository.ClinicalEncounterRepository;
import com.vn.vitalcare.care.notification.Notifier;
import com.vn.vitalcare.care.staff.service.EmployeeService;
import com.vn.vitalcare.entity.ClinicalEncounter;
import com.vn.vitalcare.entity.Customer;
import com.vn.vitalcare.entity.Employee;
import com.vn.vitalcare.entity.EncounterStatus;
import com.vn.vitalcare.entity.MedicalService;
import com.vn.vitalcare.entity.ServiceOrder;
import com.vn.vitalcare.entity.ServiceOrderStatus;
import com.vn.vitalcare.entity.StaffType;
import com.vn.vitalcare.identity.rowlevel.service.RowLevelSecurity;
import com.vn.vitalcare.identity.user.service.UserService;
import com.vn.vitalcare.share.exception.ConflictException;
import com.vn.vitalcare.share.storage.FileStorage;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

/** FE-22 Create Medical Service Order / Record Service Result / Review Result: ServiceOrders. Report-5.1, sheet BE_Services. */
class ServiceOrdersUnitTest {

    private static final Instant NOW = Instant.parse("2026-11-22T03:00:00Z");
    private ServiceOrderRepository orders;
    private ClinicalEncounterRepository encounters;
    private MedicalServiceCatalog catalog;
    private EmployeeService employees;
    private Invoices invoices;
    private Notifier notifier;
    private ServiceOrders service;
    private Employee me;
    private ClinicalEncounter encounter;
    private MedicalService xray;

    @BeforeEach
    void setUp() {
        orders = mock(ServiceOrderRepository.class);
        encounters = mock(ClinicalEncounterRepository.class);
        catalog = mock(MedicalServiceCatalog.class);
        employees = mock(EmployeeService.class);
        invoices = mock(Invoices.class);
        notifier = mock(Notifier.class);
        RowLevelSecurity rowLevel = mock(RowLevelSecurity.class);
        when(rowLevel.byId(any(), any(), any())).thenReturn((root, query, cb) -> null);
        service = new ServiceOrders(orders, mock(ServiceOrderFileRepository.class), encounters, catalog, employees,
                mock(UserService.class), mock(FileStorage.class), rowLevel, Clock.fixed(NOW, ZoneOffset.UTC),
                mock(ApplicationEventPublisher.class), invoices, notifier);
        me = new Employee();
        me.setStaffType(StaffType.DOCTOR);
        when(employees.getForCurrentUser()).thenReturn(me);
        encounter = new ClinicalEncounter();
        ReflectionTestUtils.setField(encounter, "id", 20L);
        encounter.setCustomer(new Customer());
        when(encounters.findOne(any(Specification.class))).thenReturn(Optional.of(encounter));
        xray = new MedicalService();
        xray.setName("X-quang ngực");
        xray.setPrice(new BigDecimal("150000"));
        xray.setTurnaroundMinutes(45);
        when(catalog.orderable(7L)).thenReturn(xray);
        when(orders.persist(any(ServiceOrder.class))).thenAnswer(i -> i.getArgument(0));
    }

    private ServiceOrder existing(ServiceOrderStatus status) {
        ServiceOrder o = new ServiceOrder();
        ReflectionTestUtils.setField(o, "id", 30L);
        ReflectionTestUtils.setField(o, "status", status);
        o.setEncounter(encounter);
        o.setCustomer(encounter.getCustomer());
        o.setService(xray);
        when(orders.findOne(any(Specification.class))).thenReturn(Optional.of(o));
        return o;
    }

    @Test void so01_doctorOrders_priceAndExpectedTimeFromTheCatalog_billed() {
        ServiceOrder o = service.order(20L, new ServiceOrderRequests.Order(7L, "  Chụp thẳng  "));
        assertEquals(ServiceOrderStatus.ORDERED, o.getStatus());
        assertEquals(new BigDecimal("150000"), o.getUnitPrice());
        assertEquals("Chụp thẳng", o.getInstruction());
        assertEquals(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).plusMinutes(45), o.getExpectedResultAt());
        verify(encounters).lockRow(20L);
        verify(invoices).issueForOrder(o);
    }

    @Test void so02_onlyADoctorOrders() {
        me.setStaffType(StaffType.values()[StaffType.DOCTOR.ordinal() == 0 ? 1 : 0]);
        AccessDeniedException e = assertThrows(AccessDeniedException.class,
                () -> service.order(20L, new ServiceOrderRequests.Order(7L, null)));
        assertEquals("Only a doctor orders services", e.getMessage());
        verify(orders, never()).persist(any());
    }

    @Test void so03_concludedEncounterTakesNoOrder() {
        encounter.setStatus(EncounterStatus.COMPLETED);
        ConflictException e = assertThrows(ConflictException.class,
                () -> service.order(20L, new ServiceOrderRequests.Order(7L, null)));
        assertEquals("This encounter is concluded; services can no longer be ordered in it", e.getMessage());
    }

    @Test void so04_unpaidServiceCannotStart() {
        existing(ServiceOrderStatus.ORDERED);
        ConflictException e = assertThrows(ConflictException.class, () -> service.start(30L));
        assertEquals("This service is not paid yet; it is done once the patient has paid for it", e.getMessage());
    }

    @Test void so05_paidServiceStarts_onlyOnce() {
        ServiceOrder o = existing(ServiceOrderStatus.ORDERED);
        when(invoices.orderPaid(o)).thenReturn(true);
        service.start(30L);
        assertEquals(ServiceOrderStatus.IN_PROGRESS, o.getStatus());
        assertEquals("Only an order not yet started can be started",
                assertThrows(ConflictException.class, () -> service.start(30L)).getMessage());
    }

    @Test void so06_firstResultTellsThePatient_correctionDoesNot() {
        ServiceOrder o = existing(ServiceOrderStatus.IN_PROGRESS);
        service.recordResult(30L, new ServiceOrderRequests.Result("  Phổi sáng  "));
        assertEquals(ServiceOrderStatus.RESULT_READY, o.getStatus());
        assertEquals("Phổi sáng", o.getResultText());
        service.recordResult(30L, new ServiceOrderRequests.Result("Phổi sáng, tim bình thường"));
        assertEquals("Phổi sáng, tim bình thường", o.getResultText());
        verify(notifier).notify(any(), any(), eq("result"), eq("Đã có kết quả X-quang ngực"), anyString(), any());
    }

    @Test void so06b_firstResultTellsTheOrderingDoctorOnce() {
        ServiceOrder o = existing(ServiceOrderStatus.IN_PROGRESS);
        ReflectionTestUtils.setField(o, "createdById", 9L);
        service.recordResult(30L, new ServiceOrderRequests.Result("Phổi sáng"));
        service.recordResult(30L, new ServiceOrderRequests.Result("Phổi sáng, tim bình thường"));
        verify(notifier).notify(eq(9L), any(), eq("result"), eq("Có kết quả dịch vụ bạn chỉ định: X-quang ngực"),
                anyString(), eq("/service-orders/show/30"));
    }

    @Test void so07_reviewedResultIsClosed() {
        existing(ServiceOrderStatus.REVIEWED);
        assertEquals("This order is closed; its result can no longer change", assertThrows(ConflictException.class,
                () -> service.recordResult(30L, new ServiceOrderRequests.Result("x"))).getMessage());
    }

    @Test void so08_reviewOnlyAResultThatIsIn() {
        ServiceOrder o = existing(ServiceOrderStatus.IN_PROGRESS);
        assertEquals("Only a result that is in can be reviewed",
                assertThrows(ConflictException.class, () -> service.review(30L)).getMessage());
        ReflectionTestUtils.setField(o, "status", ServiceOrderStatus.RESULT_READY);
        assertSame(o, service.review(30L));
        assertEquals(ServiceOrderStatus.REVIEWED, o.getStatus());
    }

    @Test void so09_cancelWithReason_billLineTakenOff() {
        ServiceOrder o = existing(ServiceOrderStatus.ORDERED);
        service.cancel(30L, new ServiceOrderRequests.Cancel("  Bệnh nhân từ chối  "));
        assertEquals(ServiceOrderStatus.CANCELLED, o.getStatus());
        verify(invoices).cancelForOrder(o);
    }

    @Test void so10_resultInOrEncounterConcludedCannotBeCancelled() {
        existing(ServiceOrderStatus.RESULT_READY);
        assertEquals("Only an order without a result can be cancelled", assertThrows(ConflictException.class,
                () -> service.cancel(30L, new ServiceOrderRequests.Cancel("x"))).getMessage());
        existing(ServiceOrderStatus.ORDERED);
        encounter.setStatus(EncounterStatus.COMPLETED);
        assertEquals("This encounter is concluded; its orders can no longer change", assertThrows(ConflictException.class,
                () -> service.cancel(30L, new ServiceOrderRequests.Cancel("x"))).getMessage());
    }

    @Test void so11_returnVisitOnlyOnceStarted_andForARegisteredVisit() {
        existing(ServiceOrderStatus.ORDERED);
        assertEquals("Start the service before telling the patient when to come back for its result",
                assertThrows(ConflictException.class, () -> service.visitToReturnResultsOf(30L)).getMessage());
        existing(ServiceOrderStatus.IN_PROGRESS);
        assertEquals("This order's visit was not registered; there is no visit to return to",
                assertThrows(ConflictException.class, () -> service.visitToReturnResultsOf(30L)).getMessage());
    }
}
