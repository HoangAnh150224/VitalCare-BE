package com.vn.vitalcare.care.iomt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.config.MqttProperties;
import jakarta.validation.Validation;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

/** FE-03 Vital Sign Data Collection / FE-06 Synchronization over MQTT: MqttBridge.messageArrived. Report-5.1, sheet BE_Gaps. */
class MqttBridgeUnitTest {

    private VitalIngestion ingestion;
    private MqttClient client;
    private MqttBridge bridge;

    @BeforeEach
    void setUp() {
        ingestion = mock(VitalIngestion.class);
        client = mock(MqttClient.class);
        when(client.isConnected()).thenReturn(true);
        bridge = new MqttBridge(new MqttProperties("tcp://broker:1883", "bridge", "pw", "c", "s"), ingestion,
                JsonMapper.builder().build(), Validation.buildDefaultValidatorFactory().getValidator());
        ReflectionTestUtils.setField(bridge, "client", client);
    }

    private static MqttMessage message(String body) {
        return new MqttMessage(body.getBytes(StandardCharsets.UTF_8));
    }

    private String ack() throws Exception {
        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(client).publish(eq("vitalcare/devices/VC-BAND-01/ack"), payload.capture(), eq(0), eq(false));
        return new String(payload.getValue(), StandardCharsets.UTF_8);
    }

    @Test void mq01_jsonUploadIsIngested_andAcknowledged() throws Exception {
        when(ingestion.ingest(eq("VC-BAND-01"), any(DeviceUpload.class), eq("MQTT")))
                .thenReturn(new SyncResult(5L, "SUCCESS", 1, 0, 0, 0, List.of()));
        bridge.messageArrived("vitalcare/devices/VC-BAND-01/vitals",
                message("{\"clientBatchId\":\"b1\",\"readings\":[{\"clientRecordId\":\"r1\",\"heartRate\":72}]}"));
        assertTrue(ack().contains("\"status\":\"SUCCESS\""));
    }

    @Test void mq02_moreThan500ReadingsRefused_withAFailedAck() throws Exception {
        StringBuilder readings = new StringBuilder();
        for (int i = 0; i < 501; i++) {
            readings.append(i == 0 ? "" : ",").append("{\"clientRecordId\":\"r").append(i).append("\"}");
        }
        bridge.messageArrived("vitalcare/devices/VC-BAND-01/vitals", message("{\"readings\":[" + readings + "]}"));
        verify(ingestion, never()).ingest(anyString(), any(), anyString());
        String ack = ack();
        assertTrue(ack.startsWith("{\"status\":\"FAILED\",\"error\":"));
        assertTrue(ack.contains("At most 500 readings per upload"));
    }

    @Test void mq03_brokenJsonIsAFailedAck_neverAnException() throws Exception {
        bridge.messageArrived("vitalcare/devices/VC-BAND-01/vitals", message("{not json"));
        assertTrue(ack().startsWith("{\"status\":\"FAILED\""));
    }

    @Test void mq04_statusMessageUpdatesBatteryAndFirmware() {
        bridge.messageArrived("vitalcare/devices/VC-BAND-01/status", message("{\"battery\":81,\"firmware\":\"1.4.2\"}"));
        verify(ingestion).heardFrom("VC-BAND-01", 81, "1.4.2");
        bridge.messageArrived("vitalcare/devices/VC-BAND-01/status", message("{}"));
        verify(ingestion).heardFrom("VC-BAND-01", null, null);
    }

    @Test void mq05_otherTopicsIgnored() throws Exception {
        bridge.messageArrived("vitalcare/devices/VC-BAND-01/vitals/extra", message("{}"));
        bridge.messageArrived("other/topic", message("{}"));
        verifyNoInteractions(ingestion);
        verify(client, never()).publish(anyString(), any(byte[].class), anyInt(), any(Boolean.class));
    }

    @Test void mq06_noBrokerConfigured_stateSaysOff() {
        MqttBridge off = new MqttBridge(new MqttProperties(" ", null, null, null, null), ingestion,
                JsonMapper.builder().build(), Validation.buildDefaultValidatorFactory().getValidator());
        off.start();
        assertEquals(Map.of("enabled", false, "connected", false), off.state());
    }
}
