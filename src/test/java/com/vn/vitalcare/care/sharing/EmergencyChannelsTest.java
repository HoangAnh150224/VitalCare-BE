package com.vn.vitalcare.care.sharing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class EmergencyChannelsTest {

    @SuppressWarnings("unchecked")
    private static EmergencyChannels channels(String sid, String token, String from, String baseUrl) {
        return new EmergencyChannels(mock(ObjectProvider.class), sid, token, from, "Google.vi-VN-Standard-A", baseUrl);
    }

    @Test
    void withoutTwilioTheCallIsSimulated() {
        assertThat(channels("", "", "", "http://localhost:1").call("+84912345678", "x").outcome())
                .isEqualTo("SIMULATED");
        assertThat(channels("AC1", "token", "", "http://localhost:1").call("+84912345678", "x").outcome())
                .isEqualTo("SIMULATED");
    }

    @Test
    void placesTheCallWithTheAlertReadInVietnamese() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> form = new AtomicReference<>();
        HttpServer twilio = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        twilio.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            form.set(URLDecoder.decode(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                    StandardCharsets.UTF_8));
            byte[] answer = "{\"sid\":\"CA123\",\"status\":\"queued\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(201, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        twilio.start();
        try {
            EmergencyChannels.Outcome outcome = channels("AC1", "token", "+17370000000",
                    "http://localhost:" + twilio.getAddress().getPort())
                    .call("0912345678", "Nhịp tim 150 & SpO2 < 90");

            assertThat(outcome.outcome()).isEqualTo("PLACED");
            assertThat(outcome.detail()).contains("CA123");
            assertThat(path.get()).isEqualTo("/2010-04-01/Accounts/AC1/Calls.json");
            assertThat(auth.get()).isEqualTo("Basic "
                    + Base64.getEncoder().encodeToString("AC1:token".getBytes(StandardCharsets.UTF_8)));
            assertThat(form.get()).contains("To=+84912345678", "From=+17370000000",
                    "Url=https://twimlets.com/echo?Twiml=");
            // A trial account refuses any parameter beyond these three.
            assertThat(form.get().split("&")).hasSize(3);
            assertThat(form.get()).contains("Nh%E1%BB%8Bp%20tim%20150").doesNotContain("Nh%E1%BB%8Bp+tim");
            // The script rides in the URL, so it is encoded once more than the form itself.
            assertThat(URLDecoder.decode(form.get(), StandardCharsets.UTF_8)).contains(
                    "<Say language=\"vi-VN\" voice=\"Google.vi-VN-Standard-A\" loop=\"2\">"
                            + "Nhịp tim 150 &amp; SpO2 &lt; 90</Say>");
        } finally {
            twilio.stop(0);
        }
    }

    @Test
    void aRefusalIsRecordedWithTwiliosReason() throws Exception {
        HttpServer twilio = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        twilio.createContext("/", exchange -> {
            byte[] answer = "{\"code\":21219,\"message\":\"The number is unverified.\",\"status\":400}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(400, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        twilio.start();
        try {
            EmergencyChannels.Outcome outcome = channels("AC1", "token", "+17370000000",
                    "http://localhost:" + twilio.getAddress().getPort()).call("+84912345678", "x");

            assertThat(outcome.outcome()).isEqualTo("FAILED");
            assertThat(outcome.detail()).contains("21219", "unverified");
        } finally {
            twilio.stop(0);
        }
    }

    @Test
    void phonesGoOutInE164() {
        assertThat(EmergencyChannels.e164("+84912345678")).isEqualTo("+84912345678");
        assertThat(EmergencyChannels.e164("0912345678")).isEqualTo("+84912345678");
        assertThat(EmergencyChannels.e164("84912345678")).isEqualTo("+84912345678");
    }
}
