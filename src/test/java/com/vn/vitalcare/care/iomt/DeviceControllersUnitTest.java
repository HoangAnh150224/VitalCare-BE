package com.vn.vitalcare.care.iomt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.care.device.service.DeviceService;
import com.vn.vitalcare.config.MqttProperties;
import com.vn.vitalcare.entity.DeviceAssignment;
import com.vn.vitalcare.entity.MedicalDevice;
import com.vn.vitalcare.share.exception.FieldValidationException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Report-5.1 BE_Infra2: DeviceDataController (device uploads, MQTT broker hooks) and DeviceOperationsController (FE-02 device operations). */
class DeviceControllersUnitTest {

    private final VitalIngestion ingestion = mock(VitalIngestion.class);
    private final DeviceTokens tokens = mock(DeviceTokens.class);
    private final SyncResult ok = new SyncResult(70L, "SUCCESS", 1, 0, 0, 0, List.of());

    @Nested
    @DisplayName("DeviceDataController")
    class DeviceData {

        private final MqttProperties mqtt = new MqttProperties("tcp://broker", "bridge", "bridge-pass", null, "hook-secret");
        private final DeviceDataController controller = new DeviceDataController(ingestion, tokens, mqtt, 0);
        private final DeviceUpload upload = new DeviceUpload("b-1", 0, 80, "1.0", null, List.of());

        private Map<String, Object> body(ResponseEntity<Map<String, Object>> r) {
            return r.getBody();
        }

        @Test
        @DisplayName("A batch with the device's own code and token is ingested as REST")
        void dd01_validTokenIngested() {
            when(tokens.verify("VC-BAND-01", "t")).thenReturn(true);
            when(ingestion.ingest("VC-BAND-01", upload, "REST")).thenReturn(ok);
            ResponseEntity<?> r = controller.batch("VC-BAND-01", "t", upload);
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(r.getBody()).isEqualTo(ok);
        }

        @Test
        @DisplayName("A wrong or missing token answers 401 and nothing is ingested (JSON and band log alike)")
        void dd02_badTokenRefused() {
            ResponseEntity<?> json = controller.batch("VC-BAND-01", null, upload);
            ResponseEntity<?> csv = controller.bandLog("VC-BAND-01", "wrong", "time,spo2\n");
            assertThat(json.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(csv.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(json.getBody()).isEqualTo(Map.of("message", "The device code or token is not valid"));
            verify(ingestion, never()).ingest(anyString(), any(), anyString());
        }

        @Test
        @DisplayName("MQTT auth: the bridge is a superuser; a device signs in with its upper-case code and token")
        void dd03_mqttAuth() {
            assertThat(body(controller.authenticate("hook-secret", Map.of("username", "bridge", "password", "bridge-pass"))))
                    .containsEntry("result", "allow").containsEntry("is_superuser", true);
            when(tokens.verify("VC-BAND-01", "t")).thenReturn(true);
            assertThat(body(controller.authenticate("hook-secret", Map.of("username", "VC-BAND-01", "password", "t"))))
                    .containsEntry("result", "allow").containsEntry("is_superuser", false);
        }

        @Test
        @DisplayName("MQTT auth: a lower-case code or a wrong token is denied; without the hook secret the broker gets 403")
        void dd04_mqttAuthRefused() {
            when(tokens.verify(anyString(), anyString())).thenReturn(true);
            assertThat(body(controller.authenticate("hook-secret", Map.of("username", "vc-band-01", "password", "t"))))
                    .containsEntry("result", "deny");
            assertThat(controller.authenticate("wrong", Map.of("username", "bridge", "password", "bridge-pass")).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(controller.authenticate(null, Map.of()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("MQTT ACL: a device publishes only to its own vitals and status, subscribes only to its own ack")
        void dd05_mqttAcl() {
            String own = "vitalcare/devices/VC-BAND-01/";
            assertThat(body(controller.authorise("hook-secret", Map.of("username", "vc-band-01", "topic", own + "vitals", "action", "publish"))))
                    .containsEntry("result", "allow");
            assertThat(body(controller.authorise("hook-secret", Map.of("username", "VC-BAND-01", "topic", own + "ack", "action", "subscribe"))))
                    .containsEntry("result", "allow");
            assertThat(body(controller.authorise("hook-secret", Map.of("username", "VC-BAND-01", "topic", "vitalcare/devices/VC-BAND-02/vitals", "action", "publish"))))
                    .containsEntry("result", "deny");
            assertThat(body(controller.authorise("hook-secret", Map.of("username", "VC-BAND-01", "topic", own + "vitals", "action", "subscribe"))))
                    .containsEntry("result", "deny");
            assertThat(body(controller.authorise("hook-secret", Map.of("username", "bridge", "topic", "anything", "action", "subscribe"))))
                    .containsEntry("result", "allow");
        }

        @Test
        @DisplayName("Boundary: with no hook secret configured every hook call is refused")
        void dd06_noSecretConfigured() {
            DeviceDataController open = new DeviceDataController(ingestion, tokens, new MqttProperties(null, null, null, null, ""), 0);
            assertThat(open.authorise("", Map.of()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Nested
    @DisplayName("DeviceOperationsController")
    class Operations {

        private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-22T02:00:00Z"), ZoneOffset.UTC);
        private final DeviceService devices = mock(DeviceService.class);
        private final MqttBridge bridge = mock(MqttBridge.class);
        private final VitalSignsRepository vitals = mock(VitalSignsRepository.class);
        private final DeviceOperationsController controller = new DeviceOperationsController(devices, tokens, ingestion, bridge, vitals, CLOCK);
        private final MedicalDevice band = mock(MedicalDevice.class);

        @BeforeEach
        void setUp() {
            when(band.getId()).thenReturn(3L);
            when(band.getDeviceCode()).thenReturn("VC-BAND-01");
            when(devices.get(3L)).thenReturn(band);
            when(devices.openAssignments(any())).thenReturn(Map.of());
            when(ingestion.ingest(eq("VC-BAND-01"), any(DeviceUpload.class), eq("SIMULATOR"))).thenReturn(ok);
        }

        private DeviceUpload simulated(DeviceOperationsController.SimulationRequest request) {
            controller.simulate(3L, request);
            ArgumentCaptor<DeviceUpload> upload = ArgumentCaptor.forClass(DeviceUpload.class);
            verify(ingestion).ingest(eq("VC-BAND-01"), upload.capture(), eq("SIMULATOR"));
            return upload.getValue();
        }

        @Test
        @DisplayName("A new device token comes with the MQTT user name and the topic to publish on")
        void do01_issueToken() {
            when(tokens.issue(3L)).thenReturn(new DeviceTokens.Issued("VC-BAND-01", "secret"));
            assertThat(controller.issueToken(3L)).containsEntry("token", "secret").containsEntry("mqttUsername", "VC-BAND-01")
                    .containsEntry("mqttTopic", "vitalcare/devices/VC-BAND-01/vitals");
        }

        @Test
        @DisplayName("Simulate with nothing given: 5 readings, one session, ingested as SIMULATOR")
        void do02_simulateDefaults() {
            DeviceUpload upload = simulated(new DeviceOperationsController.SimulationRequest(null, null, null, null, null, null, null, null));
            assertThat(upload.readings()).hasSize(5);
            assertThat(upload.readings()).extracting(DeviceUpload.Reading::sessionId).containsOnly(upload.session().clientSessionId());
            assertThat(upload.session().status()).isEqualTo("COMPLETED");
        }

        @Test
        @DisplayName("Boundary: 0 or 121 readings are refused (count, 1 to 120); nothing is ingested")
        void do03_countBounds() {
            assertThatThrownBy(() -> controller.simulate(3L, new DeviceOperationsController.SimulationRequest(0, null, null, null, null, null, null, null)))
                    .isInstanceOf(FieldValidationException.class).hasMessage("Between 1 and 120 readings");
            assertThatThrownBy(() -> controller.simulate(3L, new DeviceOperationsController.SimulationRequest(121, null, null, null, null, null, null, null)))
                    .isInstanceOf(FieldValidationException.class);
            verify(ingestion, never()).ingest(anyString(), any(DeviceUpload.class), anyString());
        }

        @Test
        @DisplayName("Scenario SENSOR_FAULT (any case) gives SpO2 140 and heart rate 0 exactly, with no random spread")
        void do04_scenarios() {
            DeviceUpload fault = simulated(new DeviceOperationsController.SimulationRequest(2, null, "sensor_fault", null, null, null, null, null));
            assertThat(fault.readings()).allSatisfy(r -> {
                assertThat(r.spo2()).isEqualByComparingTo("140");
                assertThat(r.heartRate()).isZero();
            });
        }

        @Test
        @DisplayName("LOW_SPO2 with the device's own alert: each reading is flagged SpO2 CRITICAL below 90")
        void do05_deviceAlert() {
            DeviceUpload low = simulated(new DeviceOperationsController.SimulationRequest(1, null, "LOW_SPO2", null, null, null, null, true));
            assertThat(low.readings().getFirst().alerts()).singleElement().satisfies(a -> {
                assertThat(a.metric()).isEqualTo("SPO2");
                assertThat(a.severity()).isEqualTo("CRITICAL");
            });
            assertThat(low.readings().getFirst().spo2()).isLessThan(new BigDecimal("90"));
        }

        @Test
        @DisplayName("Readings are squeezed in after the band was put on, never before it")
        void do06_notBeforeAssignment() {
            DeviceAssignment wearing = mock(DeviceAssignment.class);
            OffsetDateTime since = OffsetDateTime.now(CLOCK).minusMinutes(10);
            when(wearing.getAssignedAt()).thenReturn(since);
            when(devices.openAssignments(any())).thenReturn(Map.of(3L, wearing));
            DeviceUpload upload = simulated(new DeviceOperationsController.SimulationRequest(5, 3600, null, null, null, null, null, null));
            assertThat(upload.readings()).allSatisfy(r -> assertThat(OffsetDateTime.parse(r.measuredAt())).isAfter(since));
        }
    }
}
