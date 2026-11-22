package com.vn.vitalcare.care.iomt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.entity.SyncBatch;
import com.vn.vitalcare.entity.SyncFailure;
import com.vn.vitalcare.identity.rowlevel.service.RowLevelSecurity;
import com.vn.vitalcare.share.exception.ConflictException;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.LinkedMultiValueMap;
import tools.jackson.databind.ObjectMapper;

/** Report-5.1 BE_Infra2: SyncController — FE-02 device sync history and sync failures (retry, discard). */
class SyncControllerUnitTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-22T02:00:00Z"), ZoneOffset.UTC);
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-11-22T01:00:00Z");

    private final SyncBatchRepository batches = mock(SyncBatchRepository.class);
    private final SyncFailureRepository failures = mock(SyncFailureRepository.class);
    private final VitalIngestion ingestion = mock(VitalIngestion.class);
    private final RowLevelSecurity rowLevel = mock(RowLevelSecurity.class);
    private final SyncController controller = new SyncController(batches, failures, ingestion, rowLevel, new ObjectMapper(), CLOCK);
    private SyncBatch batch;
    private SyncFailure failure;

    @BeforeEach
    void setUp() {
        when(rowLevel.byId(any(), any(), any())).thenReturn((root, query, cb) -> null);
        when(rowLevel.scope(any(), any())).thenReturn((root, query, cb) -> null);
        batch = new SyncBatch(null, "MQTT", "b-1", 10, 0, AT);
        ReflectionTestUtils.setField(batch, "id", 70L);
        failure = new SyncFailure(batch, null, "r-9", "{\"spo2\":140}", "SpO2 out of range", AT);
        ReflectionTestUtils.setField(failure, "id", 8L);
    }

    @SuppressWarnings("unchecked")
    private void found(SyncFailure f) {
        when(failures.findOne(any(Specification.class))).thenReturn(Optional.ofNullable(f));
    }

    @Test
    @DisplayName("A failure is answered with its batch, reason, attempts and the original record parsed as JSON")
    void sc01_failureDetail() {
        found(failure);
        SyncController.FailureResponse r = controller.failure(8L);
        assertThat(r.batchId()).isEqualTo(70L);
        assertThat(r.reason()).isEqualTo("SpO2 out of range");
        assertThat(r.status()).isEqualTo("OPEN");
        assertThat(r.payload().get("spo2").asInt()).isEqualTo(140);
        assertThat(r.receivedAt()).isEqualTo(AT);
    }

    @Test
    @DisplayName("Boundary: a stored record that is not JSON is answered with no payload instead of failing")
    void sc02_unreadablePayload() {
        SyncFailure broken = new SyncFailure(batch, null, "r-1", "not json {", "bad", AT);
        ReflectionTestUtils.setField(broken, "id", 9L);
        found(broken);
        assertThat(controller.failure(9L).payload()).isNull();
    }

    @Test
    @DisplayName("Retry hands an open failure to the ingestion again and answers its new state")
    void sc03_retryOpen() {
        found(failure);
        when(ingestion.retry(failure)).thenAnswer(c -> {
            failure.resolve(OffsetDateTime.now(CLOCK));
            return failure;
        });
        assertThat(controller.retry(8L).status()).isEqualTo("RESOLVED");
    }

    @Test
    @DisplayName("Discard marks an open failure DISCARDED, resolved now")
    void sc04_discardOpen() {
        found(failure);
        SyncController.FailureResponse r = controller.discard(8L);
        assertThat(r.status()).isEqualTo("DISCARDED");
        assertThat(r.resolvedAt()).isEqualTo(OffsetDateTime.now(CLOCK));
    }

    @Test
    @DisplayName("A failure already dealt with cannot be retried or discarded again (409); nothing is re-ingested")
    void sc05_alreadyDealtWith() {
        failure.discard(AT);
        found(failure);
        assertThatThrownBy(() -> controller.retry(8L)).isInstanceOf(ConflictException.class)
                .hasMessage("This record has already been dealt with");
        assertThatThrownBy(() -> controller.discard(8L)).isInstanceOf(ConflictException.class);
        verify(ingestion, never()).retry(any());
    }

    @Test
    @DisplayName("A failure or batch outside the caller's scope answers 404")
    @SuppressWarnings("unchecked")
    void sc06_notFound() {
        found(null);
        when(batches.findOne(any(Specification.class))).thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller.failure(99L)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> controller.retry(99L)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> controller.batch(99L)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> controller.batchFailures(99L)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("The batch list answers a page with X-Total-Count; a batch answers its counts and source")
    @SuppressWarnings("unchecked")
    void sc07_batchList() {
        when(batches.findAll(any(Specification.class), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(batch), Pageable.ofSize(1), 41));
        LinkedMultiValueMap<String, String> query = new LinkedMultiValueMap<>();
        query.add("status", "partial");
        ResponseEntity<List<SyncController.BatchResponse>> list = controller.batches(query);
        assertThat(list.getHeaders().getFirst("X-Total-Count")).isEqualTo("41");
        assertThat(list.getBody()).singleElement().satisfies(b -> {
            assertThat(b.id()).isEqualTo(70L);
            assertThat(b.source()).isEqualTo("MQTT");
            assertThat(b.recordCount()).isEqualTo(10);
            assertThat(b.deviceId()).isNull();
        });
    }

    @Test
    @DisplayName("A batch's failures are listed after the batch itself is found")
    @SuppressWarnings("unchecked")
    void sc08_batchFailures() {
        when(batches.findOne(any(Specification.class))).thenReturn(Optional.of(batch));
        when(failures.findAll(any(Specification.class))).thenReturn(List.of(failure));
        assertThat(controller.batchFailures(70L)).singleElement().extracting(SyncController.FailureResponse::clientRecordId).isEqualTo("r-9");
    }
}
