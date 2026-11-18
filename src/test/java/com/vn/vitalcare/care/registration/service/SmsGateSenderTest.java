package com.vn.vitalcare.care.registration.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import com.vn.vitalcare.care.registration.entity.VerificationPurpose;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What goes to SMSGate and how its answer is read, against a stand-in gateway on localhost. */
class SmsGateSenderTest {

    private HttpServer server;
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> auth = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private volatile int status = 202;
    private volatile String answer = "{\"id\":\"m1\",\"state\":\"Pending\"}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] reply = answer.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private SmsGateSender sender(String password) {
        return new SmsGateSender("http://127.0.0.1:" + server.getAddress().getPort() + "/3rdparty/v1/message",
                "user", password, new OtpProperties(6, Duration.ofMinutes(5), 5, Duration.ofSeconds(60), 5));
    }

    @Test
    @DisplayName("posts the text and the number in E.164 with the account's basic credentials; a 2xx is accepted")
    void accepted() {
        SmsGateSender sender = sender("secret");
        String text = sender.message("123456", VerificationPurpose.REGISTER);

        assertNull(sender.deliver("0912345678", text));

        assertEquals("/3rdparty/v1/message", path.get());
        assertEquals("Basic " + Base64.getEncoder().encodeToString("user:secret".getBytes(StandardCharsets.UTF_8)),
                auth.get());
        String sent = body.get();
        assertTrue(sent.contains("\"phoneNumbers\":[\"+84912345678\"]"), sent);
        assertTrue(sent.contains("\"textMessage\":{\"text\":\"" + text + "\"}"), sent);
        assertTrue(text.contains("123456") && text.length() <= 160 && text.chars().allMatch(c -> c < 128), text);
        String reset = sender.message("123456", VerificationPurpose.RESET_PASSWORD);
        assertTrue(reset.contains("123456") && reset.length() <= 160 && reset.chars().allMatch(c -> c < 128), reset);
    }

    @Test
    @DisplayName("a refusal comes back with its status and what the gateway said, also for a 401")
    void refused() {
        status = 401;
        answer = "{\"message\":\"Unauthorized\"}";

        assertEquals("HTTP 401 {\"message\":\"Unauthorized\"}", sender("wrong").deliver("+84912345678", "x"));
    }

    @Test
    @DisplayName("a username without a password stops the application at start-up")
    void passwordRequired() {
        assertThrows(IllegalStateException.class, () -> sender(" "));
    }
}
