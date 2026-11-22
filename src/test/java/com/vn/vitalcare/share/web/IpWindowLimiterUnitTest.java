package com.vn.vitalcare.share.web;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vn.vitalcare.share.exception.ConflictException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Report-5.1 BE_Infra: IpWindowLimiter, the rolling-window cap the open endpoints use instead of a captcha. */
class IpWindowLimiterUnitTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-22T02:00:00Z"));
    private final Clock clock = new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };
    private final IpWindowLimiter limiter = new IpWindowLimiter(Duration.ofHours(1), 3, "Too many requests", clock);

    @Test
    @DisplayName("Up to max requests in the window are let through")
    void ip01_upToMaxPasses() {
        assertThatCode(() -> {
            limiter.check("1.2.3.4");
            limiter.check("1.2.3.4");
            limiter.check("1.2.3.4");
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("The request after max inside the window is refused with the limiter's own message")
    void ip02_overMaxRefused() {
        for (int i = 0; i < 3; i++) {
            limiter.check("1.2.3.4");
        }
        assertThatThrownBy(() -> limiter.check("1.2.3.4"))
                .isInstanceOf(ConflictException.class).hasMessage("Too many requests");
    }

    @Test
    @DisplayName("Each key counts on its own: one address using up its window does not stop another")
    void ip03_keysAreIndependent() {
        for (int i = 0; i < 3; i++) {
            limiter.check("1.2.3.4");
        }
        assertThatCode(() -> limiter.check("5.6.7.8")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Boundary: a request exactly one window old still counts; one second later it has rolled off")
    void ip04_windowRollsOff() {
        for (int i = 0; i < 3; i++) {
            limiter.check("k");
        }
        now.set(now.get().plus(Duration.ofHours(1)));
        assertThatThrownBy(() -> limiter.check("k")).isInstanceOf(ConflictException.class);
        now.set(now.get().plusSeconds(1));
        assertThatCode(() -> limiter.check("k")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("A refused request is not counted: once the window rolls, the key gets its full allowance back")
    void ip05_refusalNotCounted() {
        for (int i = 0; i < 3; i++) {
            limiter.check("k");
        }
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> limiter.check("k")).isInstanceOf(ConflictException.class);
        }
        now.set(now.get().plus(Duration.ofHours(2)));
        assertThatCode(() -> {
            limiter.check("k");
            limiter.check("k");
            limiter.check("k");
        }).doesNotThrowAnyException();
    }
}
