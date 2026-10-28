package com.vn.vitalcare.care.billing.sepay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** With no account configured, the account to pay into is read from SePay's bank account list. */
class SepayAccountTest {

    private static final String LIST = """
            {"status":200,"error":null,"messages":{"success":true},"bankaccounts":[
              {"id":"1","account_holder_name":"nguyen van old","account_number":"1111","bank_short_name":"ACB","bank_bin":"970416","active":"0"},
              {"id":"2","account_holder_name":"Vu Hoang Anh","account_number":"0987654321","bank_short_name":"MBBank","bank_bin":"970422","active":"1"},
              {"id":"3","account_holder_name":"Vu Hoang Anh","account_number":"5555","bank_short_name":"TPBank","bank_bin":"970423","active":"1"}
            ]}""";

    private HttpServer server;
    private final AtomicReference<String> answer = new AtomicReference<>(LIST);
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> authorization = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/userapi/bankaccounts/list", exchange -> {
            calls.incrementAndGet();
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = answer.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private BankTransfer transfer(String accountNumber, String token) {
        return new BankTransfer("", accountNumber, "", "whsec_test", token, "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @Test
    @DisplayName("the first active account, asked once with the token as Bearer, then remembered")
    void firstActiveAccount() {
        BankTransfer transfer = transfer("", "tok_123");

        assertTrue(transfer.enabled());
        BankTransfer.Instructions instructions = transfer.instructionsFor(17L, 2000);
        assertEquals("970422", instructions.bank());
        assertEquals("MBBank", instructions.bankName());
        assertEquals("0987654321", instructions.accountNumber());
        assertEquals("VU HOANG ANH", instructions.accountName());
        assertEquals("VC000017", instructions.content());
        assertEquals("Bearer tok_123", authorization.get());
        transfer.enabled();
        assertEquals(1, calls.get(), "asked once, then remembered");
    }

    @Test
    @DisplayName("APP_TRANSFER_ACCOUNT_NUMBER picks that account among SePay's")
    void namedAccount() {
        assertEquals("970423", transfer("5555", "tok_123").instructionsFor(1L, 2000).bank());
    }

    @Test
    @DisplayName("no token, SePay refusing, or no active account: no transfer offered, and not asked again at once")
    void nothingToOffer() {
        assertFalse(transfer("", "").enabled(), "no token");
        assertEquals(0, calls.get());

        status.set(401);
        answer.set("{\"status\":401}");
        BankTransfer refused = transfer("", "bad");
        assertFalse(refused.enabled());
        assertFalse(refused.enabled());
        assertEquals(1, calls.get(), "a failure is not retried on every request");

        status.set(200);
        answer.set("{\"bankaccounts\":[{\"account_number\":\"1\",\"active\":\"0\"}]}");
        assertFalse(transfer("", "tok").enabled(), "only a locked account");
    }

    @Test
    @DisplayName("an account set in the environment wins, and SePay is not asked")
    void configuredWins() {
        BankTransfer transfer = new BankTransfer("MB", "0123", "a", "s", "tok", "http://127.0.0.1:" + server.getAddress().getPort());
        assertEquals("MB", transfer.instructionsFor(1L, 1).bank());
        assertEquals(0, calls.get());
    }
}
