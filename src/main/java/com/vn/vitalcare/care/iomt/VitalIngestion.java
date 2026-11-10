package com.vn.vitalcare.care.iomt;

import com.vn.vitalcare.care.assignment.repository.DeviceAssignmentRepository;
import com.vn.vitalcare.care.device.repository.MedicalDeviceRepository;
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
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * The one way a reading gets in (FE-03, FE-06), whatever brought it — REST
 * from the phone, MQTT from the device, the simulator: check it, refuse a
 * repeat, store it for whoever wore the device when it was taken, and keep a
 * record of the upload and of every reading refused, to retry later.
 *
 * <p>The device row is locked for the upload, so two uploads of the same
 * readings — a retry racing its original, an MQTT redelivery — are taken one
 * after the other and the second finds them already stored.
 */
@Service
public class VitalIngestion {

    public static final String ACCEPTED = "ACCEPTED";
    public static final String DUPLICATE = "DUPLICATE";
    public static final String INVALID = "INVALID";
    public static final String REJECTED = "REJECTED";

    /** Outside these a reading is a fault of the sensor, not a measurement (kept, marked INVALID). */
    static final BigDecimal SPO2_MIN = new BigDecimal("50"), SPO2_MAX = new BigDecimal("100");
    static final int HEART_RATE_MIN = 20, HEART_RATE_MAX = 250;
    static final BigDecimal SKIN_TEMP_MIN = new BigDecimal("25"), SKIN_TEMP_MAX = new BigDecimal("45");
    static final BigDecimal BODY_TEMP_MIN = new BigDecimal("34"), BODY_TEMP_MAX = new BigDecimal("43");
    /** A device clock a little ahead is tolerated; a reading from further in the future is refused. */
    private static final long FUTURE_TOLERANCE_MINUTES = 5;
    /** Older than this it is not monitoring any more; refused. */
    private static final long MAX_AGE_DAYS = 30;

    private final MedicalDeviceRepository devices;
    private final DeviceAssignmentRepository assignments;
    private final VitalSignsRepository vitals;
    private final MeasurementSessionRepository sessions;
    private final SyncBatchRepository batches;
    private final SyncFailureRepository failures;
    private final ApplicationEventPublisher events;
    private final ObjectMapper json;
    private final Clock clock;
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    public VitalIngestion(MedicalDeviceRepository devices, DeviceAssignmentRepository assignments,
                          VitalSignsRepository vitals, MeasurementSessionRepository sessions,
                          SyncBatchRepository batches, SyncFailureRepository failures,
                          ApplicationEventPublisher events, ObjectMapper json, Clock clock) {
        this.devices = devices;
        this.assignments = assignments;
        this.vitals = vitals;
        this.sessions = sessions;
        this.batches = batches;
        this.failures = failures;
        this.events = events;
        this.json = json;
        this.clock = clock;
    }

    /** Takes in an upload for the device with that code; 404 when there is none. */
    @Transactional
    public SyncResult ingest(String deviceCode, DeviceUpload upload, String source) {
        return ingest(deviceCode, upload, source, null);
    }

    /**
     * @param onlyFor when the upload comes from a patient's app rather than the device itself: the
     *                caller's own hand-over. A reading from outside it — another wearer's days — is
     *                refused, so an account can never write into somebody else's record.
     */
    @Transactional
    public SyncResult ingest(String deviceCode, DeviceUpload upload, String source, DeviceAssignment onlyFor) {
        MedicalDevice device = lockByCode(deviceCode);
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<DeviceUpload.Reading> readings = upload.readings() == null ? List.of() : upload.readings();

        device.heardFrom(now, upload.battery(), upload.firmware());
        SyncBatch batch = batches.persist(new SyncBatch(device, source, trim(upload.clientBatchId(), 64),
                readings.size(), upload.pendingOnDevice(), now));

        Intake intake = new Intake(device, batch, source, now, onlyFor);
        List<SyncResult.RecordResult> results = new ArrayList<>(readings.size());
        int invalid = 0;
        for (DeviceUpload.Reading reading : readings) {
            SyncResult.RecordResult result = intake.take(reading, upload.session());
            if (REJECTED.equals(result.outcome())) {
                failures.persist(new SyncFailure(batch, device, trim(reading.clientRecordId(), 64),
                        payload(reading), result.reason(), now));
                batch.rejected();
            } else if (DUPLICATE.equals(result.outcome())) {
                batch.duplicate();
            } else {
                batch.accepted();
                if (INVALID.equals(result.outcome())) {
                    invalid++;
                }
            }
            results.add(result);
        }
        intake.closeSession(upload.session());
        if (batch.getAcceptedCount() > 0) {
            device.setLastSyncAt(now);
        }
        batch.close(device.getStatus() == DeviceStatus.RETIRED ? "Thiết bị đã ngừng sử dụng" : null);
        // Whose upload it was: the last reading's wearer, else whoever wears the device now (a batch of repeats).
        DeviceAssignment owner = intake.lastCustomer != null ? intake.lastCustomer : intake.wearerAt(now);
        if (batch.getCustomer() == null && owner != null) {
            batch.setCustomer(owner.getCustomer());
        }
        return new SyncResult(batch.getId(), batch.getStatus(), batch.getAcceptedCount() - invalid,
                batch.getDuplicateCount(), invalid, batch.getRejectedCount(), results);
    }

    /** A device said it is there (MQTT status, a heartbeat), without readings. */
    @Transactional
    public void heardFrom(String deviceCode, Integer battery, String firmware) {
        lockByCode(deviceCode).heardFrom(OffsetDateTime.now(clock), battery, firmware);
    }

    /**
     * One refused reading through the intake again (FE-06 Synchronization
     * Management): taken in now that what stopped it is put right, or tried
     * once more and left open with the new reason.
     */
    @Transactional
    public SyncFailure retry(SyncFailure failure) {
        MedicalDevice device = devices.findForUpdate(failure.getDevice().getId())
                .orElseThrow(() -> new ResourceNotFoundException("Device", failure.getDevice().getId()));
        // Loaded with the failure before the lock: read again now that it is held.
        entityManager.refresh(device);
        OffsetDateTime now = OffsetDateTime.now(clock);
        DeviceUpload.Reading reading;
        try {
            reading = json.readValue(failure.getPayload(), DeviceUpload.Reading.class);
        } catch (RuntimeException e) {
            failure.triedAgain("Không đọc được dữ liệu gốc", now);
            return failure;
        }
        SyncResult.RecordResult result = new Intake(device, failure.getBatch(), failure.getBatch().getSource(), now, null)
                .take(reading, null);
        if (REJECTED.equals(result.outcome())) {
            failure.triedAgain(result.reason(), now);
        } else {
            failure.resolve(now);
            failure.getBatch().recovered(DUPLICATE.equals(result.outcome()));
            if (ACCEPTED.equals(result.outcome())) {
                device.setLastSyncAt(now);
            }
        }
        return failure;
    }

    private MedicalDevice lockByCode(String deviceCode) {
        String code = deviceCode == null ? "" : deviceCode.trim().toUpperCase(Locale.ROOT);
        return devices.findForUpdateByCode(code)
                .orElseThrow(() -> new ResourceNotFoundException("Device", code));
    }

    private String payload(DeviceUpload.Reading reading) {
        try {
            return json.writeValueAsString(reading);
        } catch (RuntimeException e) {
            return "{}";
        }
    }

    private static String trim(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String t = value.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }

    /** One upload's worth of readings for one device: its wearers and sessions looked up once. */
    private final class Intake {

        private final MedicalDevice device;
        private final SyncBatch batch;
        private final String source;
        private final OffsetDateTime now;
        private final List<DeviceAssignment> wearers;
        private final Map<String, MeasurementSession> sessionsById = new HashMap<>();
        private final DeviceAssignment onlyFor;
        private DeviceAssignment lastCustomer;

        Intake(MedicalDevice device, SyncBatch batch, String source, OffsetDateTime now, DeviceAssignment onlyFor) {
            this.onlyFor = onlyFor;
            this.device = device;
            this.batch = batch;
            this.source = source;
            this.now = now;
            this.wearers = assignments.findByDeviceIdAndDeletedAtIsNullOrderByAssignedAtDesc(device.getId());
        }

        SyncResult.RecordResult take(DeviceUpload.Reading reading, DeviceUpload.Session session) {
            String id = trim(reading.clientRecordId(), 64);
            if (device.getStatus() == DeviceStatus.RETIRED) {
                return refused(id, "Thiết bị đã ngừng sử dụng");
            }
            if (id == null) {
                return refused(null, "Thiếu mã bản ghi (clientRecordId)");
            }
            OffsetDateTime measuredAt;
            try {
                measuredAt = reading.measuredAt() == null ? null : OffsetDateTime.parse(reading.measuredAt().trim());
            } catch (DateTimeParseException e) {
                return refused(id, "Thời điểm đo không đúng định dạng ISO-8601");
            }
            if (measuredAt == null) {
                return refused(id, "Thiếu thời điểm đo (measuredAt)");
            }
            if (measuredAt.isAfter(now.plusMinutes(FUTURE_TOLERANCE_MINUTES))) {
                return refused(id, "Thời điểm đo ở tương lai — đồng hồ thiết bị bị lệch");
            }
            if (measuredAt.isBefore(now.minusDays(MAX_AGE_DAYS))) {
                return refused(id, "Bản ghi cũ hơn " + MAX_AGE_DAYS + " ngày");
            }
            if (reading.spo2() == null && reading.heartRate() == null && reading.skinTemperature() == null
                    && reading.bodyTemperature() == null) {
                return refused(id, "Bản ghi không có chỉ số nào");
            }
            DeviceAssignment wearer = wearerAt(measuredAt);
            if (onlyFor != null && (wearer == null || !wearer.getId().equals(onlyFor.getId()))) {
                return refused(id, "Thời điểm đo nằm ngoài thời gian bạn được giao thiết bị");
            }
            if (vitals.existsByDeviceIdAndClientRecordId(device.getId(), id)) {
                return new SyncResult.RecordResult(id, DUPLICATE, null, null);
            }
            if (wearer == null) {
                return refused(id, "Thiết bị chưa được giao cho bệnh nhân nào vào thời điểm đo");
            }
            lastCustomer = wearer;

            VitalSigns vital = new VitalSigns();
            vital.setDevice(device);
            vital.setCustomer(wearer.getCustomer());
            vital.setDeviceAssignment(wearer);
            vital.setSpo2(reading.spo2());
            vital.setHeartRate(reading.heartRate());
            vital.setTemperature(reading.skinTemperature());
            vital.setBodyTemperature(reading.bodyTemperature());
            vital.setMeasuredAt(measuredAt);
            vital.setReceivedAt(now);
            vital.setClientRecordId(id);
            vital.setSource(source);
            vital.setSession(sessionFor(reading.sessionId(), session, wearer, measuredAt));
            String implausible = implausible(reading);
            if (implausible != null) {
                vital.markInvalid(implausible);
            }
            VitalSigns saved = vitals.save(vital);
            if (saved.isValid()) {
                events.publishEvent(new VitalEvents.Recorded(saved.getId(), wearer.getCustomer().getId(),
                        device.getId()));
            }
            return new SyncResult.RecordResult(id, saved.isValid() ? ACCEPTED : INVALID, implausible, saved.getId());
        }

        /** Ends the sitting the upload says is over. */
        void closeSession(DeviceUpload.Session session) {
            if (session == null || session.clientSessionId() == null || session.status() == null) {
                return;
            }
            String status = session.status().trim().toUpperCase(Locale.ROOT);
            if (!status.equals(MeasurementSession.COMPLETED) && !status.equals(MeasurementSession.CANCELLED)) {
                return;
            }
            sessions.findByDeviceIdAndClientSessionId(device.getId(), session.clientSessionId().trim())
                    .filter(s -> MeasurementSession.IN_PROGRESS.equals(s.getStatus()))
                    .ifPresent(s -> s.end(status, parseOr(session.endedAt(), now)));
        }

        private MeasurementSession sessionFor(String sessionId, DeviceUpload.Session described,
                                              DeviceAssignment wearer, OffsetDateTime measuredAt) {
            String key = trim(sessionId, 64);
            if (key == null) {
                return null;
            }
            MeasurementSession session = sessionsById.computeIfAbsent(key, k ->
                    sessions.findByDeviceIdAndClientSessionId(device.getId(), k).orElseGet(() -> {
                        OffsetDateTime started = described != null && k.equals(described.clientSessionId())
                                ? parseOr(described.startedAt(), measuredAt) : measuredAt;
                        return sessions.save(new MeasurementSession(device, wearer.getCustomer(), wearer, k, started));
                    }));
            session.covers(measuredAt);
            return session;
        }

        /** Who wore the device at that moment: handed over at or before it, not yet returned. */
        private DeviceAssignment wearerAt(OffsetDateTime when) {
            for (DeviceAssignment a : wearers) {
                boolean started = a.getAssignedAt() == null || !a.getAssignedAt().isAfter(when);
                boolean notEnded = a.getUnassignedAt() == null || a.getUnassignedAt().isAfter(when);
                if (started && notEnded) {
                    return a;
                }
            }
            return null;
        }

        private SyncResult.RecordResult refused(String id, String reason) {
            return new SyncResult.RecordResult(id, REJECTED, reason, null);
        }
    }

    /** Why a reading cannot be a real measurement, or null when it can. */
    static String implausible(DeviceUpload.Reading r) {
        if (r.spo2() != null && (r.spo2().compareTo(SPO2_MIN) < 0 || r.spo2().compareTo(SPO2_MAX) > 0)) {
            return "SpO₂ " + r.spo2().stripTrailingZeros().toPlainString() + "% ngoài khoảng đo được (50–100)";
        }
        if (r.heartRate() != null && (r.heartRate() < HEART_RATE_MIN || r.heartRate() > HEART_RATE_MAX)) {
            return "Nhịp tim " + r.heartRate() + " ngoài khoảng đo được (20–250)";
        }
        if (r.skinTemperature() != null
                && (r.skinTemperature().compareTo(SKIN_TEMP_MIN) < 0 || r.skinTemperature().compareTo(SKIN_TEMP_MAX) > 0)) {
            return "Nhiệt độ da " + r.skinTemperature().stripTrailingZeros().toPlainString()
                    + "°C ngoài khoảng đo được (25–45)";
        }
        if (r.bodyTemperature() != null
                && (r.bodyTemperature().compareTo(BODY_TEMP_MIN) < 0 || r.bodyTemperature().compareTo(BODY_TEMP_MAX) > 0)) {
            return "Nhiệt độ cơ thể " + r.bodyTemperature().stripTrailingZeros().toPlainString()
                    + "°C ngoài khoảng đo được (34–43)";
        }
        return null;
    }

    private static OffsetDateTime parseOr(String value, OffsetDateTime fallback) {
        try {
            return value == null || value.isBlank() ? fallback : OffsetDateTime.parse(value.trim());
        } catch (DateTimeParseException e) {
            return fallback;
        }
    }
}
