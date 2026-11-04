package com.vn.vitalcare.care.iomt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.care.assignment.repository.DeviceAssignmentRepository;
import com.vn.vitalcare.care.device.repository.MedicalDeviceRepository;
import com.vn.vitalcare.entity.Customer;
import com.vn.vitalcare.entity.DeviceAssignment;
import com.vn.vitalcare.entity.DeviceStatus;
import com.vn.vitalcare.entity.MeasurementSession;
import com.vn.vitalcare.entity.MedicalDevice;
import com.vn.vitalcare.entity.SyncBatch;
import com.vn.vitalcare.entity.SyncFailure;
import com.vn.vitalcare.entity.VitalSigns;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

/** FE-03/FE-06: what the intake keeps, refuses and records, whatever brought the readings. */
class VitalIngestionTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 11, 2, 9, 0, 0, 0, ZoneOffset.ofHours(7));

    private MedicalDeviceRepository devices;
    private DeviceAssignmentRepository assignments;
    private VitalSignsRepository vitals;
    private MeasurementSessionRepository sessions;
    private SyncBatchRepository batches;
    private SyncFailureRepository failures;
    private ApplicationEventPublisher events;
    private VitalIngestion ingestion;

    private MedicalDevice device;
    private Customer patient;
    private final List<VitalSigns> stored = new ArrayList<>();
    private final Set<String> storedIds = new HashSet<>();
    private final List<SyncFailure> refused = new ArrayList<>();

    @BeforeEach
    void setUp() {
        devices = mock(MedicalDeviceRepository.class);
        assignments = mock(DeviceAssignmentRepository.class);
        vitals = mock(VitalSignsRepository.class);
        sessions = mock(MeasurementSessionRepository.class);
        batches = mock(SyncBatchRepository.class);
        failures = mock(SyncFailureRepository.class);
        events = mock(ApplicationEventPublisher.class);
        ingestion = new VitalIngestion(devices, assignments, vitals, sessions, batches, failures, events,
                JsonMapper.builder().build(), Clock.fixed(Instant.from(NOW), ZoneOffset.UTC));
        ReflectionTestUtils.setField(ingestion, "entityManager", mock(jakarta.persistence.EntityManager.class));

        device = new MedicalDevice();
        ReflectionTestUtils.setField(device, "id", 7L);
        device.setDeviceCode("VC-BAND-07");
        device.setStatus(DeviceStatus.ASSIGNED);
        when(devices.findForUpdateByCode("VC-BAND-07")).thenReturn(Optional.of(device));
        when(devices.findForUpdate(7L)).thenReturn(Optional.of(device));

        patient = new Customer();
        ReflectionTestUtils.setField(patient, "id", 37L);
        DeviceAssignment wearing = new DeviceAssignment();
        wearing.setCustomer(patient);
        wearing.setDevice(device);
        wearing.setAssignedAt(NOW.minusDays(2));
        when(assignments.findByDeviceIdAndDeletedAtIsNullOrderByAssignedAtDesc(7L)).thenReturn(List.of(wearing));

        when(batches.persist(any(SyncBatch.class))).thenAnswer(call -> {
            SyncBatch batch = call.getArgument(0);
            ReflectionTestUtils.setField(batch, "id", 100L);
            return batch;
        });
        when(failures.persist(any(SyncFailure.class))).thenAnswer(call -> {
            refused.add(call.getArgument(0));
            return call.getArgument(0);
        });
        when(vitals.existsByDeviceIdAndClientRecordId(eq(7L), anyString()))
                .thenAnswer(call -> storedIds.contains(call.<String>getArgument(1)));
        when(vitals.save(any(VitalSigns.class))).thenAnswer(call -> {
            VitalSigns vital = call.getArgument(0);
            ReflectionTestUtils.setField(vital, "id", (long) stored.size() + 1);
            stored.add(vital);
            storedIds.add(vital.getClientRecordId());
            return vital;
        });
        when(sessions.findByDeviceIdAndClientSessionId(anyLong(), anyString())).thenReturn(Optional.empty());
        when(sessions.save(any(MeasurementSession.class))).thenAnswer(call -> call.getArgument(0));
    }

    private static DeviceUpload.Reading reading(String id, OffsetDateTime at, String spo2, Integer heartRate) {
        return new DeviceUpload.Reading(id, at == null ? null : at.toString(),
                spo2 == null ? null : new BigDecimal(spo2), heartRate, new BigDecimal("33.4"), null);
    }

    private static DeviceUpload upload(DeviceUpload.Reading... readings) {
        return new DeviceUpload("b-1", 3, 64, "1.2.0", null, List.of(readings));
    }

    @Test
    @DisplayName("Valid readings are stored for the wearer, announced, and the device is marked online")
    void storesValidReadings() {
        SyncResult result = ingestion.ingest("vc-band-07", upload(
                reading("r1", NOW.minusMinutes(2), "97", 76),
                reading("r2", NOW.minusMinutes(1), "96.5", 78)), "REST");

        assertThat(result.status()).isEqualTo(SyncBatch.SUCCESS);
        assertThat(result.accepted()).isEqualTo(2);
        assertThat(stored).allSatisfy(v -> {
            assertThat(v.getCustomer()).isSameAs(patient);
            assertThat(v.isValid()).isTrue();
            assertThat(v.getSource()).isEqualTo("REST");
        });
        verify(events, times(2)).publishEvent(any(VitalEvents.Recorded.class));
        assertThat(device.getConnectivity()).isEqualTo(MedicalDevice.ONLINE);
        assertThat(device.getBatteryLevel()).isEqualTo(64);
        assertThat(device.getLastSyncAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("The same reading sent again is not stored twice")
    void refusesRepeats() {
        ingestion.ingest("VC-BAND-07", upload(reading("r1", NOW.minusMinutes(2), "97", 76)), "MQTT");
        SyncResult again = ingestion.ingest("VC-BAND-07", upload(reading("r1", NOW.minusMinutes(2), "97", 76)), "MQTT");

        assertThat(stored).hasSize(1);
        assertThat(again.duplicates()).isEqualTo(1);
        assertThat(again.records().getFirst().outcome()).isEqualTo(VitalIngestion.DUPLICATE);
        assertThat(again.status()).isEqualTo(SyncBatch.SUCCESS);
    }

    @Test
    @DisplayName("An implausible value is kept, marked INVALID, and never announced as a measurement")
    void marksImplausibleReadings() {
        SyncResult result = ingestion.ingest("VC-BAND-07", upload(reading("r1", NOW.minusMinutes(1), "140", 76)), "REST");

        assertThat(result.invalid()).isEqualTo(1);
        assertThat(result.records().getFirst().outcome()).isEqualTo(VitalIngestion.INVALID);
        assertThat(stored.getFirst().getValidationStatus()).isEqualTo(VitalSigns.INVALID);
        assertThat(stored.getFirst().getValidationError()).contains("SpO₂");
        verify(events, never()).publishEvent(any(VitalEvents.Recorded.class));
    }

    @Test
    @DisplayName("Malformed readings are refused one by one and kept as failures; the rest of the upload goes in")
    void refusesMalformedReadingsAlone() {
        SyncResult result = ingestion.ingest("VC-BAND-07", upload(
                reading("ok", NOW.minusMinutes(1), "97", 76),
                reading(null, NOW.minusMinutes(1), "97", 76),
                reading("no-time", null, "97", 76),
                reading("future", NOW.plusHours(1), "97", 76),
                new DeviceUpload.Reading("empty", NOW.minusMinutes(1).toString(), null, null, null, null)), "REST");

        assertThat(result.status()).isEqualTo(SyncBatch.PARTIAL);
        assertThat(result.accepted()).isEqualTo(1);
        assertThat(result.rejected()).isEqualTo(4);
        assertThat(refused).extracting(SyncFailure::getReason).allSatisfy(reason -> assertThat(reason).isNotBlank());
        assertThat(refused).extracting(SyncFailure::getPayload).allSatisfy(p -> assertThat(p).startsWith("{"));
    }

    @Test
    @DisplayName("A reading from before the device was handed over belongs to nobody: refused, then taken on retry once it is")
    void readingWithoutWearerIsRetried() {
        SyncResult result = ingestion.ingest("VC-BAND-07",
                upload(reading("early", NOW.minusDays(3), "97", 76)), "REST");
        assertThat(result.status()).isEqualTo(SyncBatch.FAILED);
        SyncFailure failure = refused.getFirst();
        assertThat(failure.getReason()).contains("chưa được giao");

        // Still nobody's: tried again, still open.
        ingestion.retry(failure);
        assertThat(failure.getStatus()).isEqualTo(SyncFailure.OPEN);
        assertThat(failure.getAttempts()).isEqualTo(2);

        // The hand-over is put right (it began earlier): the retry takes it in.
        DeviceAssignment earlier = new DeviceAssignment();
        earlier.setCustomer(patient);
        earlier.setDevice(device);
        earlier.setAssignedAt(NOW.minusDays(5));
        when(assignments.findByDeviceIdAndDeletedAtIsNullOrderByAssignedAtDesc(7L)).thenReturn(List.of(earlier));
        ingestion.retry(failure);

        assertThat(failure.getStatus()).isEqualTo(SyncFailure.RESOLVED);
        assertThat(failure.getBatch().getStatus()).isEqualTo(SyncBatch.SUCCESS);
        assertThat(stored).extracting(VitalSigns::getClientRecordId).containsExactly("early");
    }

    @Test
    @DisplayName("A retired device's readings are all refused")
    void retiredDevice() {
        device.setStatus(DeviceStatus.RETIRED);
        SyncResult result = ingestion.ingest("VC-BAND-07", upload(reading("r1", NOW.minusMinutes(1), "97", 76)), "MQTT");

        assertThat(result.status()).isEqualTo(SyncBatch.FAILED);
        assertThat(stored).isEmpty();
    }

    @Test
    @DisplayName("Readings of one sitting share a session, closed when the upload says it is complete")
    void sessions() {
        DeviceUpload.Session sitting = new DeviceUpload.Session("s-1", NOW.minusMinutes(5).toString(), null, "COMPLETED");
        ArgumentCaptor<MeasurementSession> saved = ArgumentCaptor.forClass(MeasurementSession.class);
        when(sessions.findByDeviceIdAndClientSessionId(7L, "s-1")).thenReturn(Optional.empty());

        ingestion.ingest("VC-BAND-07", new DeviceUpload("b-2", 0, null, null, sitting, List.of(
                new DeviceUpload.Reading("a", NOW.minusMinutes(4).toString(), new BigDecimal("97"), 70, null, "s-1"),
                new DeviceUpload.Reading("b", NOW.minusMinutes(3).toString(), new BigDecimal("98"), 72, null, "s-1"))),
                "REST");

        verify(sessions, times(1)).save(saved.capture());
        assertThat(stored).allSatisfy(v -> assertThat(v.getSession()).isSameAs(saved.getValue()));
    }

    @Test
    @DisplayName("A patient's app cannot write into a former wearer's days with the same band")
    void appCannotBackdateIntoAnotherPatient() {
        Customer former = new Customer();
        ReflectionTestUtils.setField(former, "id", 99L);
        DeviceAssignment theirs = new DeviceAssignment();
        ReflectionTestUtils.setField(theirs, "id", 1L);
        theirs.setCustomer(former);
        theirs.setDevice(device);
        theirs.setAssignedAt(NOW.minusDays(20));
        theirs.setUnassignedAt(NOW.minusDays(10));
        DeviceAssignment mine = new DeviceAssignment();
        ReflectionTestUtils.setField(mine, "id", 2L);
        mine.setCustomer(patient);
        mine.setDevice(device);
        mine.setAssignedAt(NOW.minusDays(2));
        when(assignments.findByDeviceIdAndDeletedAtIsNullOrderByAssignedAtDesc(7L)).thenReturn(List.of(mine, theirs));

        SyncResult result = ingestion.ingest("VC-BAND-07", upload(
                reading("forged", NOW.minusDays(15), "97", 76),
                reading("mine", NOW.minusHours(1), "97", 76)), "REST", mine);

        assertThat(result.records()).extracting(SyncResult.RecordResult::outcome)
                .containsExactly(VitalIngestion.REJECTED, VitalIngestion.ACCEPTED);
        assertThat(stored).extracting(v -> v.getCustomer().getId()).containsExactly(37L);
        // The device's own upload (no app in between) still files it under whoever wore it then.
        ingestion.ingest("VC-BAND-07", upload(reading("band", NOW.minusDays(15), "97", 76)), "MQTT");
        assertThat(stored.getLast().getCustomer().getId()).isEqualTo(99L);
    }

    @Test
    @DisplayName("An unknown device code is a 404")
    void unknownDevice() {
        when(devices.findForUpdateByCode("NOPE")).thenReturn(Optional.empty());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ingestion.ingest("nope", upload(), "REST"))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
