package com.vn.vitalcare.care.registration.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import com.vn.vitalcare.care.registration.entity.VerificationPurpose;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What goes to eSMS and how its answer is read, against a stand-in eSMS on localhost. */
class EsmsOtpSenderTest {

    private HttpServer server;
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private volatile String answer;
    private volatile String contentType = "application/json";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] reply = answer.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(200, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private EsmsOtpSender sender(String brandname) {
        return new EsmsOtpSender("KEY", "SECRET", brandname, "2",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/",
                "", false, new OtpProperties(6, Duration.ofMinutes(5), 5, Duration.ofSeconds(60), 5));
    }

    @Test
    @DisplayName("sends the national number, unaccented text and the account's keys; 100 is accepted")
    void accepted() {
        answer = "{\"CodeResult\":\"100\",\"SMSID\":\"abc\"}";
        EsmsOtpSender sender = sender("VITALCARE");
        String text = sender.message("123456", VerificationPurpose.RESET_PASSWORD);

        assertNull(sender.deliver("+84912345678", text));

        assertEquals("/MainService.svc/json/SendMultipleMessage_V4_post_json/", path.get());
        String sent = body.get();
        assertTrue(sent.contains("\"Phone\":\"0912345678\""), sent);
        assertTrue(sent.contains("\"ApiKey\":\"KEY\"") && sent.contains("\"SecretKey\":\"SECRET\""), sent);
        assertTrue(sent.contains("\"Brandname\":\"VITALCARE\"") && sent.contains("\"SmsType\":\"2\""), sent);
        assertTrue(sent.contains("\"IsUnicode\":\"0\""), sent);
        assertTrue(text.contains("123456") && text.length() <= 160 && text.chars().allMatch(c -> c < 128), text);
    }

    @Test
    @DisplayName("any other CodeResult is a refusal, with its reason")
    void refused() {
        answer = "{\"CodeResult\":\"104\",\"ErrorMessage\":\"Brandname khong ton tai\"}";

        assertEquals("CodeResult=104 Brandname khong ton tai", sender("X").deliver("+84912345678", "x"));
    }

    @Test
    @DisplayName("JSON labelled as text is still read")
    void jsonAsText() {
        answer = "{\"CodeResult\":\"100\"}";
        contentType = "text/html; charset=utf-8";

        assertNull(sender("X").deliver("+84912345678", "x"));
    }

    @Test
    @DisplayName("no brandname is sent when none is set (another SmsType); SmsType 2 without one fails at start-up")
    void brandname() {
        answer = "{\"CodeResult\":\"100\"}";
        EsmsOtpSender noBrand = new EsmsOtpSender("KEY", "SECRET", "", "8",
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "", false, new OtpProperties(6, Duration.ofMinutes(5), 5, Duration.ofSeconds(60), 5));
        noBrand.deliver("+84912345678", "x");

        assertFalse(body.get().contains("Brandname"), body.get());
        assertThrows(IllegalStateException.class, () -> sender(""));
    }

    @Test
    @DisplayName("a configured template is the whole text, the code put in place of {code}")
    void template() {
        OtpProperties otp = new OtpProperties(6, Duration.ofMinutes(5), 5, Duration.ofSeconds(60), 5);
        EsmsOtpSender sender = new EsmsOtpSender("KEY", "SECRET", "Baotrixemay", "2", "http://localhost",
                "{code} la ma xac minh dang ky Baotrixemay cua ban", false, otp);

        assertEquals("123456 la ma xac minh dang ky Baotrixemay cua ban",
                sender.message("123456", VerificationPurpose.RESET_PASSWORD));
        assertThrows(IllegalStateException.class, () -> new EsmsOtpSender(
                "KEY", "SECRET", "B", "2", "http://localhost", "no placeholder", false, otp));
    }

    @Test
    @DisplayName("sandbox mode asks eSMS for a test send; off, the flag is not sent at all")
    void sandbox() {
        answer = "{\"CodeResult\":\"100\"}";
        OtpProperties otp = new OtpProperties(6, Duration.ofMinutes(5), 5, Duration.ofSeconds(60), 5);
        String url = "http://127.0.0.1:" + server.getAddress().getPort();

        new EsmsOtpSender("KEY", "SECRET", "B", "2", url, "", true, otp).deliver("+84912345678", "x");
        assertTrue(body.get().contains("\"Sandbox\":\"1\""), body.get());

        new EsmsOtpSender("KEY", "SECRET", "B", "2", url, "", false, otp).deliver("+84912345678", "x");
        assertFalse(body.get().contains("Sandbox"), body.get());
    }
}
