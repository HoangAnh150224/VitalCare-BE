package com.vn.vitalcare.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.mqtt.*}: the broker the server reads device readings from
 * (EMQX). With no {@code url} the MQTT side is off and REST still takes
 * readings — what a deployment without a broker runs.
 *
 * @param url        e.g. {@code tcp://localhost:1883}; blank turns MQTT off
 * @param username   the server's own account on the broker (a superuser in the auth hook)
 * @param hookSecret what the broker sends in {@code X-Hook-Secret} on its auth/ACL calls; blank refuses them all
 */
@ConfigurationProperties(prefix = "app.mqtt")
public record MqttProperties(String url, String username, String password, String clientId, String hookSecret) {

    public static final String TOPIC_ROOT = "vitalcare/devices";

    public boolean enabled() {
        return url != null && !url.isBlank();
    }

    public String clientIdOrDefault() {
        return clientId == null || clientId.isBlank() ? "vitalcare-be" : clientId;
    }

    /** Whether these are the server's own broker credentials. */
    public boolean isBridge(String user, String pass) {
        return username != null && !username.isBlank() && password != null && username.equals(user)
                && pass != null && MessageDigest.isEqual(password.getBytes(StandardCharsets.UTF_8),
                pass.getBytes(StandardCharsets.UTF_8));
    }
}
