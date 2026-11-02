package com.vn.vitalcare.care.iomt;

import com.vn.vitalcare.config.MqttProperties;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads devices' MQTT messages into the same intake REST uses (FE-03):
 * {@code vitalcare/devices/{code}/vitals} carries a {@link DeviceUpload} and is
 * answered on {@code .../ack} with its {@link SyncResult};
 * {@code .../status} carries {@code {"battery":..,"firmware":..}}.
 *
 * <p>Off unless {@code app.mqtt.url} is set; a broker that is down is retried
 * by the client, and REST keeps working meanwhile. QoS 1: a message may come
 * twice, which the intake's duplicate check absorbs.
 * ponytail: one subscriber per server; a second node needs a shared subscription ($share/...).
 */
@Component
public class MqttBridge implements MqttCallbackExtended {

    private static final Logger log = LoggerFactory.getLogger(MqttBridge.class);

    private final MqttProperties properties;
    private final VitalIngestion ingestion;
    private final ObjectMapper json;
    private final jakarta.validation.Validator validator;
    private volatile MqttClient client;
    private volatile boolean stopping;

    public MqttBridge(MqttProperties properties, VitalIngestion ingestion, ObjectMapper json,
                      jakarta.validation.Validator validator) {
        this.properties = properties;
        this.ingestion = ingestion;
        this.json = json;
        this.validator = validator;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!properties.enabled()) {
            log.info("MQTT is off (app.mqtt.url is not set); readings come in over REST only");
            return;
        }
        if (properties.hookSecret() == null || properties.hookSecret().isBlank()) {
            log.error("app.mqtt.hook-secret is not set: the broker's auth/ACL hooks are refused and no device can"
                    + " sign in over MQTT. Set APP_MQTT_HOOK_SECRET and configure EMQX to send it (docs/mobile-api.md).");
        }
        try {
            client = new MqttClient(properties.url(), properties.clientIdOrDefault(), new MemoryPersistence());
        } catch (MqttException e) {
            log.warn("MQTT is off: {} is not a broker address", properties.url(), e);
            return;
        }
        client.setCallback(this);
        // Never wait forever on the broker from the callback thread (an ack publish waiting for its
        // PUBACK while the receiver is blocked on a full inbound queue would stall the connection).
        client.setTimeToWait(5_000);
        // Paho reconnects by itself only after a first connection; until then this keeps trying, so a
        // broker that starts after the server (or is down for a while) is picked up without a restart.
        Thread.ofVirtual().name("mqtt-connect").start(this::connectUntilUp);
    }

    private void connectUntilUp() {
        MqttConnectOptions options = new MqttConnectOptions();
        options.setAutomaticReconnect(true);
        options.setCleanSession(true);
        if (properties.username() != null && !properties.username().isBlank()) {
            options.setUserName(properties.username());
            options.setPassword(properties.password() == null ? new char[0] : properties.password().toCharArray());
        }
        long waitMs = 5_000;
        while (!stopping && !client.isConnected()) {
            try {
                client.connect(options);
                return;
            } catch (MqttException e) {
                log.warn("MQTT broker at {} not reachable ({}); REST only, retrying in {} s",
                        properties.url(), e.getMessage(), waitMs / 1000);
            }
            try {
                Thread.sleep(waitMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            waitMs = Math.min(waitMs * 2, 60_000);
        }
    }

    /** Subscribed again on every (re)connect: a clean session forgets subscriptions. */
    @Override
    public void connectComplete(boolean reconnect, String serverUri) {
        try {
            client.subscribe(MqttProperties.TOPIC_ROOT + "/+/vitals", 1);
            client.subscribe(MqttProperties.TOPIC_ROOT + "/+/status", 1);
            log.info("MQTT {} to {}", reconnect ? "reconnected" : "connected", serverUri);
        } catch (MqttException e) {
            log.warn("Could not subscribe to device topics", e);
        }
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        String[] parts = topic.split("/");
        if (parts.length != 4) {
            return;
        }
        String code = parts[2];
        String body = new String(message.getPayload(), StandardCharsets.UTF_8);
        try {
            if ("vitals".equals(parts[3])) {
                DeviceUpload upload = json.readValue(body, DeviceUpload.class);
                // The same limits REST's @Valid applies (at most 500 readings, field lengths).
                var violations = validator.validate(upload);
                if (!violations.isEmpty()) {
                    var first = violations.iterator().next();
                    throw new IllegalArgumentException(first.getPropertyPath() + ": " + first.getMessage());
                }
                SyncResult result = ingestion.ingest(code, upload, "MQTT");
                publish(MqttProperties.TOPIC_ROOT + "/" + code + "/ack", json.writeValueAsString(result));
            } else if ("status".equals(parts[3])) {
                JsonNode status = json.readTree(body);
                ingestion.heardFrom(code,
                        status.hasNonNull("battery") ? status.get("battery").asInt() : null,
                        status.hasNonNull("firmware") ? status.get("firmware").asString() : null);
            }
        } catch (RuntimeException e) {
            // Never thrown back into Paho: that would drop the connection.
            log.warn("Could not take MQTT message on {}: {}", topic, e.getMessage());
            publish(MqttProperties.TOPIC_ROOT + "/" + code + "/ack",
                    "{\"status\":\"FAILED\",\"error\":" + quote(e.getMessage()) + "}");
        }
    }

    private void publish(String topic, String payload) {
        try {
            if (client != null && client.isConnected()) {
                // QoS 0: an ack is advisory (the device resends; duplicates are absorbed), and a QoS 0
                // publish completes once written, without waiting on the broker from this thread.
                client.publish(topic, payload.getBytes(StandardCharsets.UTF_8), 0, false);
            }
        } catch (MqttException e) {
            log.debug("Could not publish to {}", topic, e);
        }
    }

    private String quote(String value) {
        try {
            return json.writeValueAsString(value == null ? "error" : value);
        } catch (RuntimeException e) {
            return "\"error\"";
        }
    }

    @Override
    public void connectionLost(Throwable cause) {
        log.warn("MQTT connection lost; reconnecting: {}", cause == null ? "" : cause.getMessage());
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
    }

    /** For the health of things: whether readings can come in over MQTT now. */
    public Map<String, Object> state() {
        return Map.of("enabled", properties.enabled(), "connected", client != null && client.isConnected());
    }

    @PreDestroy
    public void stop() {
        stopping = true;
        try {
            if (client != null && client.isConnected()) {
                client.disconnect();
            }
        } catch (MqttException e) {
            log.debug("MQTT disconnect", e);
        }
    }
}
