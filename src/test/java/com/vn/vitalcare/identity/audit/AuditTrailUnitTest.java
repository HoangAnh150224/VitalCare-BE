package com.vn.vitalcare.identity.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.vn.vitalcare.entity.AuditLog;
import com.vn.vitalcare.share.audit.AuditEvent;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import com.vn.vitalcare.share.storage.LocalFileStorage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * FE-20 Audit Log: what each audit row records (AuditTrail, AuditWriter);
 * FE-22 the stored result files (LocalFileStorage). Report-5.1, sheet BE_Gaps.
 */
class AuditTrailUnitTest {

    private static final Instant NOW = Instant.parse("2026-11-22T03:00:00Z");
    private AuditWriter writer;
    private AuditTrail trail;

    @BeforeEach
    void setUp() {
        writer = mock(AuditWriter.class);
        ObjectMapper json = JsonMapper.builder().build();
        trail = new AuditTrail(writer, json, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
    }

    private AuditLog written() {
        ArgumentCaptor<AuditLog> row = ArgumentCaptor.forClass(AuditLog.class);
        verify(writer).write(row.capture());
        return row.getValue();
    }

    @Test void at01_eventBecomesARow_statesAsJson_timeFromTheClock() {
        trail.on(new AuditEvent("alert_rules", 7L, "update", Map.of("threshold", 90), Map.of("threshold", 92)));
        AuditLog row = written();
        assertEquals("alert_rules", row.getEntityType());
        assertEquals(7L, row.getEntityId());
        assertEquals("update", row.getAction());
        assertEquals("{\"threshold\":90}", row.getBeforeState());
        assertEquals("{\"threshold\":92}", row.getAfterState());
        assertEquals(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC), row.getOccurredAt());
        assertNull(row.getIpAddress());
    }

    @Test void at02_requestDetails_lastForwardedAddress_longAgentCut() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "203.0.113.9, 10.0.0.2");
        request.addHeader("User-Agent", "A".repeat(600));
        request.addHeader("X-Request-Id", "req-1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        trail.record("users", 1L, "delete", null, null);
        AuditLog row = written();
        assertEquals("10.0.0.2", row.getIpAddress());
        assertEquals(512, row.getUserAgent().length());
        assertEquals("req-1", row.getRequestId());
        assertNull(row.getBeforeState());
    }

    @Test void at03_anAddressThatIsNotAnAddressIsDropped() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "<script>");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        trail.record("users", 1L, "update", null, null);
        assertNull(written().getIpAddress());
    }

    @Test void at04_aFailedWriteNeverFailsTheAction() {
        doThrow(new IllegalStateException("db down")).when(writer).write(any());
        trail.record("users", 1L, "update", null, null);
    }

    @Test void at05_writerSavesTheRow() {
        AuditLogRepository logs = mock(AuditLogRepository.class);
        AuditLog row = new AuditLog(1L, "Admin", "users", 1L, "update", null, null, OffsetDateTime.now(), null, null, null);
        new AuditWriter(logs).write(row);
        verify(logs).save(row);
    }

    @Test void fs01_storeThenLoad_deleteRemoves(@TempDir Path dir) throws Exception {
        LocalFileStorage storage = new LocalFileStorage(dir.toString());
        String key = storage.store(new ByteArrayInputStream("kết quả".getBytes(StandardCharsets.UTF_8)));
        assertTrue(key.matches("[0-9a-f-]{36}"));
        assertEquals("kết quả", new String(storage.load(key).getContentAsByteArray(), StandardCharsets.UTF_8));
        storage.delete(key);
        assertTrue(Files.notExists(dir.resolve(key)));
        assertThrows(ResourceNotFoundException.class, () -> storage.load(key));
    }

    @Test void fs02_aKeyThatIsNotOursIsNotFound_neverAPath(@TempDir Path dir) throws Exception {
        LocalFileStorage storage = new LocalFileStorage(dir.toString());
        Files.writeString(dir.resolve("secret.txt"), "x");
        assertThrows(ResourceNotFoundException.class, () -> storage.load("../secret.txt"));
        assertThrows(ResourceNotFoundException.class, () -> storage.load("secret.txt"));
        assertThrows(ResourceNotFoundException.class, () -> storage.load(null));
        storage.delete("secret.txt");
        assertTrue(Files.exists(dir.resolve("secret.txt")));
    }
}
