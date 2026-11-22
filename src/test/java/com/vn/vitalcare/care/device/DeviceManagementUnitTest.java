package com.vn.vitalcare.care.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.care.clinic.service.ClinicService;
import com.vn.vitalcare.care.device.dto.DevicePatchRequest;
import com.vn.vitalcare.care.device.dto.DeviceRequest;
import com.vn.vitalcare.care.assignment.repository.DeviceAssignmentRepository;
import com.vn.vitalcare.care.device.repository.MedicalDeviceRepository;
import com.vn.vitalcare.care.device.service.impl.DeviceServiceImpl;
import com.vn.vitalcare.care.iomt.DeviceHeartbeat;
import com.vn.vitalcare.care.notification.Notifier;
import com.vn.vitalcare.care.staff.service.ClinicScope;
import com.vn.vitalcare.config.MqttProperties;
import com.vn.vitalcare.entity.Clinic;
import com.vn.vitalcare.entity.Customer;
import com.vn.vitalcare.entity.DeviceAssignment;
import com.vn.vitalcare.entity.DeviceStatus;
import com.vn.vitalcare.entity.MedicalDevice;
import com.vn.vitalcare.entity.NotificationLevel;
import com.vn.vitalcare.identity.rowlevel.service.RowLevelSecurity;
import com.vn.vitalcare.identity.user.entity.User;
import com.vn.vitalcare.share.exception.ConflictException;
import com.vn.vitalcare.share.exception.FieldValidationException;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * FE-02 IoMT Device Management: Register / Update IoMT Device, Device Status Monitoring,
 * Device Assignment History. Report-5.1_Unit Test.xlsx, sheet BE_Devices (Note column = test name).
 */
class DeviceManagementUnitTest {

    private static final Instant NOW = Instant.parse("2026-11-22T03:00:00Z");
    private static final UUID CLINIC = UUID.fromString("5b1f8c2e-6a3d-4f7e-9c41-2d8e0a7b3c19");

    private MedicalDeviceRepository repository;
    private DeviceAssignmentRepository assignments;
    private ClinicService clinics;
    private ClinicScope scope;
    private RowLevelSecurity rowLevel;
    private DeviceServiceImpl service;
    private Clinic clinic;

    @BeforeEach
    void setUp() {
        repository = mock(MedicalDeviceRepository.class);
        assignments = mock(DeviceAssignmentRepository.class);
        clinics = mock(ClinicService.class);
        scope = mock(ClinicScope.class);
        rowLevel = mock(RowLevelSecurity.class);
        when(rowLevel.byId(any(), any(), any())).thenReturn((root, query, cb) -> null);
        clinic = new Clinic();
        clinic.setClinicId(CLINIC);
        when(scope.currentClinicId()).thenReturn(Optional.of(CLINIC));
        when(clinics.get(CLINIC)).thenReturn(clinic);
        when(repository.persist(any(MedicalDevice.class))).thenAnswer(i -> i.getArgument(0));
        service = new DeviceServiceImpl(repository, assignments, clinics, scope, rowLevel,
                Clock.fixed(NOW, ZoneOffset.UTC));
        ReflectionTestUtils.setField(service, "mqtt", new MqttProperties("tcp://broker", "vitalcare-bridge", "pw", "c", "s"));
    }

    private static DeviceRequest request(String code, String serial) {
        return new DeviceRequest(code, serial, "Vòng đeo tay", "VitalCare", "VC-Band 1");
    }

    private MedicalDevice existing(long id, DeviceStatus status) {
        MedicalDevice d = new MedicalDevice();
        ReflectionTestUtils.setField(d, "id", id);
        d.setDeviceCode("VC-BAND-01");
        d.setSerialNumber("SN-1");
        d.setStatus(status);
        when(repository.findOne(any(Specification.class))).thenReturn(Optional.of(d));
        when(repository.findForUpdate(id)).thenReturn(Optional.of(d));
        when(repository.exists(any(Specification.class))).thenReturn(true);
        return d;
    }

    @Nested
    @DisplayName("DeviceRequest (@Valid in DeviceController.create)")
    class RequestValidation {
        private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

        private Set<String> errors(DeviceRequest r) {
            return validator.validate(r).stream().map(v -> v.getPropertyPath() + ": " + v.getMessage())
                    .collect(Collectors.toSet());
        }

        @Test void dv01_validRequest() {
            assertEquals(Set.of(), errors(request("VC-BAND-01", "SN-1")));
        }

        @Test void dv02_everyFieldRequired() {
            assertEquals(Set.of("deviceCode: Vui lòng nhập mã thiết bị.", "serialNumber: Vui lòng nhập số serial.",
                    "deviceType: Vui lòng nhập loại thiết bị.", "manufacturer: Vui lòng nhập hãng sản xuất.",
                    "model: Vui lòng nhập model."), errors(new DeviceRequest(" ", "", " ", "", " ")));
        }

        @Test void dv03_code64Accepted_65Refused() {
            assertEquals(Set.of(), errors(request("A".repeat(64), "SN")));
            assertEquals(Set.of("deviceCode: Device code must be at most 64 characters"), errors(request("A".repeat(65), "SN")));
        }

        @Test void dv04_serial128Accepted_129Refused() {
            assertEquals(Set.of(), errors(request("C", "S".repeat(128))));
            assertEquals(Set.of("serialNumber: Serial number must be at most 128 characters"), errors(request("C", "S".repeat(129))));
        }

        @Test void dv05_patchBlankFieldRefused() {
            var v = validator.validate(new DevicePatchRequest("  ", null, null, null, null)).stream()
                    .map(x -> x.getPropertyPath() + ": " + x.getMessage()).collect(Collectors.toSet());
            assertEquals(Set.of("serialNumber: Vui lòng nhập số serial."), v);
        }
    }

    @Nested
    @DisplayName("DeviceServiceImpl.register — Register IoMT Device")
    class Register {

        @Test void dv06_registersAvailableAtTheDesksClinic() {
            MedicalDevice d = service.register(request("  vc-band-01 ", " SN-1 "));
            assertEquals("VC-BAND-01", d.getDeviceCode());
            assertEquals("SN-1", d.getSerialNumber());
            assertEquals(DeviceStatus.AVAILABLE, d.getStatus());
            assertSame(clinic, d.getClinic());
            assertEquals(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC), d.getRegisteredAt());
            verify(repository).flush();
            verify(rowLevel).checkWritable(d);
        }

        @Test void dv07_duplicateCodeRefused() {
            when(repository.existsByDeviceCodeIgnoreCase("VC-BAND-01")).thenReturn(true);
            FieldValidationException e = assertThrows(FieldValidationException.class,
                    () -> service.register(request("vc-band-01", "SN-2")));
            assertEquals("deviceCode", e.field());
            assertEquals("A device with that code already exists", e.getMessage());
            verify(repository, never()).persist(any());
        }

        @Test void dv08_brokerAccountCodeIsReserved() {
            FieldValidationException e = assertThrows(FieldValidationException.class,
                    () -> service.register(request("VITALCARE-BRIDGE", "SN-2")));
            assertEquals("That code is reserved", e.getMessage());
        }

        @Test void dv09_duplicateSerialRefused() {
            when(repository.existsBySerialNumberIgnoreCase("SN-1")).thenReturn(true);
            FieldValidationException e = assertThrows(FieldValidationException.class,
                    () -> service.register(request("VC-NEW", "SN-1")));
            assertEquals("serialNumber", e.field());
        }

        @Test void dv10_noClinicOfMyOwn_firstClinicTaken() {
            when(scope.currentClinicId()).thenReturn(Optional.empty());
            Clinic first = new Clinic();
            when(clinics.listAll()).thenReturn(List.of(first));
            assertSame(first, service.register(request("VC-X", "SN-X")).getClinic());
        }
    }

    @Nested
    @DisplayName("DeviceServiceImpl.update — Update IoMT Device")
    class Update {

        @Test void dv11_detailsChanged_blankBecomesEmpty() {
            MedicalDevice d = existing(3L, DeviceStatus.AVAILABLE);
            service.update(3L, new DevicePatchRequest(null, " Vòng đeo ", "", "VC-Band 2", null));
            assertEquals("Vòng đeo", d.getDeviceType());
            assertNull(d.getManufacturer());
            assertEquals("VC-Band 2", d.getModel());
            assertEquals("SN-1", d.getSerialNumber());
        }

        @Test void dv12_serialOfAnotherDeviceRefused() {
            existing(3L, DeviceStatus.AVAILABLE);
            when(repository.existsBySerialNumberIgnoreCase("SN-9")).thenReturn(true);
            assertThrows(FieldValidationException.class,
                    () -> service.update(3L, new DevicePatchRequest("SN-9", null, null, null, null)));
        }

        @Test void dv13_sameSerialOtherCaseAccepted() {
            MedicalDevice d = existing(3L, DeviceStatus.AVAILABLE);
            when(repository.existsBySerialNumberIgnoreCase(anyString())).thenReturn(true);
            service.update(3L, new DevicePatchRequest("sn-1", null, null, null, null));
            assertEquals("sn-1", d.getSerialNumber());
        }

        @Test void dv14_unknownOrOutOfScopeIs404() {
            when(repository.findOne(any(Specification.class))).thenReturn(Optional.empty());
            assertThrows(ResourceNotFoundException.class,
                    () -> service.update(99L, new DevicePatchRequest(null, "x", null, null, null)));
        }
    }

    @Nested
    @DisplayName("DeviceServiceImpl.update(status) — device status")
    class Status {

        @Test void dv15_availableToMaintenance() {
            MedicalDevice d = existing(3L, DeviceStatus.AVAILABLE);
            service.update(3L, new DevicePatchRequest(null, null, null, null, DeviceStatus.MAINTENANCE));
            assertEquals(DeviceStatus.MAINTENANCE, d.getStatus());
        }

        @Test void dv16_assignedOnlyByHandingToAPatient() {
            existing(3L, DeviceStatus.AVAILABLE);
            FieldValidationException e = assertThrows(FieldValidationException.class,
                    () -> service.update(3L, new DevicePatchRequest(null, null, null, null, DeviceStatus.ASSIGNED)));
            assertEquals("A device becomes assigned by handing it to a patient", e.getMessage());
        }

        @Test void dv17_deviceOnAPatientCannotBeRetired() {
            existing(3L, DeviceStatus.ASSIGNED);
            ConflictException e = assertThrows(ConflictException.class,
                    () -> service.update(3L, new DevicePatchRequest(null, null, null, null, DeviceStatus.RETIRED)));
            assertEquals("This device is on a patient; take it back first", e.getMessage());
        }

        @Test void dv18_openAssignmentAlsoBlocks() {
            existing(3L, DeviceStatus.AVAILABLE);
            when(assignments.existsByDeviceIdAndUnassignedAtIsNullAndDeletedAtIsNull(3L)).thenReturn(true);
            assertThrows(ConflictException.class,
                    () -> service.update(3L, new DevicePatchRequest(null, null, null, null, DeviceStatus.MAINTENANCE)));
        }

        @Test void dv19_lockedRowOutsideMyScopeIs404() {
            existing(3L, DeviceStatus.AVAILABLE);
            when(repository.exists(any(Specification.class))).thenReturn(false);
            assertThrows(ResourceNotFoundException.class,
                    () -> service.update(3L, new DevicePatchRequest(null, null, null, null, DeviceStatus.RETIRED)));
        }
    }

    @Nested
    @DisplayName("DeviceServiceImpl.history / openAssignments — Device Assignment History")
    class History {

        @Test void dv20_historyNewestFirst() {
            existing(3L, DeviceStatus.AVAILABLE);
            DeviceAssignment a = new DeviceAssignment();
            when(assignments.findByDeviceIdAndDeletedAtIsNullOrderByAssignedAtDesc(3L)).thenReturn(List.of(a));
            assertEquals(List.of(a), service.history(3L));
        }

        @Test void dv21_historyOfInvisibleDeviceIs404() {
            when(repository.findOne(any(Specification.class))).thenReturn(Optional.empty());
            assertThrows(ResourceNotFoundException.class, () -> service.history(3L));
            verifyNoInteractions(assignments);
        }

        @Test void dv22_openAssignmentsKeyedByDevice_emptyAsksNothing() {
            assertEquals(Map.of(), service.openAssignments(List.of()));
            verifyNoInteractions(assignments);
            MedicalDevice d = new MedicalDevice();
            ReflectionTestUtils.setField(d, "id", 3L);
            DeviceAssignment open = new DeviceAssignment();
            open.setDevice(d);
            when(assignments.findByDeviceIdInAndUnassignedAtIsNullAndDeletedAtIsNull(anyList())).thenReturn(List.of(open));
            assertEquals(Map.of(3L, open), service.openAssignments(List.of(3L)));
        }
    }

    @Nested
    @DisplayName("MedicalDevice.heardFrom / DeviceHeartbeat.sweep — Device Status Monitoring")
    class Connectivity {

        @Test void dv23_heardFromGoesOnline_batteryClamped_firmwareTrimmed() {
            MedicalDevice d = new MedicalDevice();
            OffsetDateTime at = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
            d.heardFrom(at, 140, "  " + "f".repeat(70) + " ");
            assertEquals(MedicalDevice.ONLINE, d.getConnectivity());
            assertEquals(100, d.getBatteryLevel());
            assertEquals(64, d.getFirmwareVersion().length());
            assertEquals(at, d.getLastSeenAt());
            d.heardFrom(at, -5, " ");
            assertEquals(0, d.getBatteryLevel());
            assertEquals(64, d.getFirmwareVersion().length(), "a blank firmware keeps the last one");
        }

        @Test void dv24_quietDeviceMarkedOffline_wearerWarned() {
            MedicalDevice d = new MedicalDevice();
            ReflectionTestUtils.setField(d, "id", 3L);
            d.setDeviceCode("VC-BAND-01");
            User account = mock(User.class);
            when(account.getId()).thenReturn(42L);
            Customer wearer = mock(Customer.class);
            when(wearer.getUser()).thenReturn(account);
            DeviceAssignment open = new DeviceAssignment();
            open.setDevice(d);
            open.setCustomer(wearer);
            when(repository.findGoneQuiet(any())).thenReturn(List.of(d));
            when(repository.markOffline(eq(3L), any())).thenReturn(1);
            when(assignments.findByDeviceIdInAndUnassignedAtIsNullAndDeletedAtIsNull(anyList())).thenReturn(List.of(open));
            Notifier notifier = mock(Notifier.class);
            new DeviceHeartbeat(repository, assignments, notifier, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(5)).sweep();
            verify(notifier).notify(eq(42L), eq(NotificationLevel.WARNING), eq("device"),
                    eq("Thiết bị VC-BAND-01 mất kết nối"), anyString(), eq("/my-devices"));
        }

        @Test void dv25_alreadyMarkedByAnotherNode_noSecondWarning() {
            MedicalDevice d = new MedicalDevice();
            ReflectionTestUtils.setField(d, "id", 3L);
            when(repository.findGoneQuiet(any())).thenReturn(List.of(d));
            when(repository.markOffline(eq(3L), any())).thenReturn(0);
            Notifier notifier = mock(Notifier.class);
            new DeviceHeartbeat(repository, assignments, notifier, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(5)).sweep();
            verifyNoInteractions(notifier);
        }
    }
}
