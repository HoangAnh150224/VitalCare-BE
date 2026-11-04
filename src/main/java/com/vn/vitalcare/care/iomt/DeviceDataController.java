package com.vn.vitalcare.care.iomt;

import com.vn.vitalcare.config.MqttProperties;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What a device calls without a user's sign-in (FE-03): its readings over REST,
 * and the broker's questions about it over MQTT. Public in SecurityConfig; the
 * device proves itself with its own secret ({@link DeviceTokens}), the broker
 * with the hook secret it is configured with.
 */
@RestController
@RequestMapping("/api")
public class DeviceDataController {

    private final VitalIngestion ingestion;
    private final DeviceTokens tokens;
    private final MqttProperties mqtt;
    private final int minConfidence;

    public DeviceDataController(VitalIngestion ingestion, DeviceTokens tokens, MqttProperties mqtt,
                                @org.springframework.beans.factory.annotation.Value("${app.iomt.min-confidence:0}")
                                int minConfidence) {
        this.ingestion = ingestion;
        this.tokens = tokens;
        this.mqtt = mqtt;
        this.minConfidence = minConfidence;
    }

    /** A device (or a gateway) uploading straight to the server. 401 for a wrong secret. */
    @PostMapping(value = "/device_data/batch", consumes = "application/json")
    public ResponseEntity<?> batch(@RequestHeader(name = "X-Device-Code", required = false) String code,
                                   @RequestHeader(name = "X-Device-Token", required = false) String token,
                                   @Valid @RequestBody DeviceUpload upload) {
        if (!tokens.verify(code, token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "The device code or token is not valid"));
        }
        return ResponseEntity.ok(ingestion.ingest(code, upload, "REST"));
    }

    /** The same, with the band's own log as it keeps it ({@link BandLog}, {@code text/csv}). */
    @PostMapping(value = "/device_data/batch", consumes = {"text/csv", "text/plain"})
    public ResponseEntity<?> bandLog(@RequestHeader(name = "X-Device-Code", required = false) String code,
                                     @RequestHeader(name = "X-Device-Token", required = false) String token,
                                     @RequestBody String log) {
        if (!tokens.verify(code, token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "The device code or token is not valid"));
        }
        return ResponseEntity.ok(ingestion.ingest(code, BandLog.parse(code, log, minConfidence), "REST"));
    }

    /** EMQX HTTP authentication: a device by its code and secret, the server's own bridge by its account. */
    @PostMapping("/mqtt/auth")
    public ResponseEntity<Map<String, Object>> authenticate(
            @RequestHeader(name = "X-Hook-Secret", required = false) String hookSecret,
            @RequestBody Map<String, String> body) {
        if (!hookAllowed(hookSecret)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        String username = java.util.Objects.toString(body.get("username"), "");
        String password = java.util.Objects.toString(body.get("password"), "");
        if (mqtt.isBridge(username, password)) {
            return ResponseEntity.ok(Map.of("result", "allow", "is_superuser", true));
        }
        // A device signs in under its code exactly as stored (upper case), so no device can ever
        // present itself under the bridge's name, which the ACL hook recognises.
        boolean device = username.equals(username.toUpperCase(java.util.Locale.ROOT))
                && tokens.verify(username, password);
        return ResponseEntity.ok(Map.of("result", device ? "allow" : "deny", "is_superuser", false));
    }

    /** EMQX HTTP authorisation: a device publishes only under its own code and reads only its own acks. */
    @PostMapping("/mqtt/acl")
    public ResponseEntity<Map<String, Object>> authorise(
            @RequestHeader(name = "X-Hook-Secret", required = false) String hookSecret,
            @RequestBody Map<String, String> body) {
        if (!hookAllowed(hookSecret)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        String username = java.util.Objects.toString(body.get("username"), "");
        String topic = java.util.Objects.toString(body.get("topic"), "");
        String action = java.util.Objects.toString(body.get("action"), "");
        // The server's own bridge account (signed in through /mqtt/auth as a superuser) reads every
        // device's topics and answers on their acks; EMQX skips this call for superusers, others may not.
        if (mqtt.username() != null && !mqtt.username().isBlank() && mqtt.username().equals(username)) {
            return ResponseEntity.ok(Map.of("result", "allow"));
        }
        String own = MqttProperties.TOPIC_ROOT + "/" + username.trim().toUpperCase(java.util.Locale.ROOT) + "/";
        boolean allowed = switch (action) {
            case "publish" -> topic.equals(own + "vitals") || topic.equals(own + "status");
            case "subscribe" -> topic.equals(own + "ack");
            default -> false;
        };
        return ResponseEntity.ok(Map.of("result", allowed ? "allow" : "deny"));
    }

    /** Closed without a configured secret: these endpoints are public, and the broker must prove itself. */
    private boolean hookAllowed(String presented) {
        String expected = mqtt.hookSecret();
        if (expected == null || expected.isBlank()) {
            return false;
        }
        return presented != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }
}
