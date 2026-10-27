package com.vn.vitalcare.care.registration.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.vn.vitalcare.share.exception.ConflictException;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** One client address gets a fixed number of code requests per rolling hour. */
class OtpIpLimiterTest {

    private final RegistrationServiceTest.MutableClock clock =
            new RegistrationServiceTest.MutableClock(Instant.parse("2026-10-27T03:00:00Z"));
    private final OtpIpLimiter limiter = new OtpIpLimiter(3, clock);

    @Test
    @DisplayName("past the cap an address is refused, others are not; an hour later it may ask again")
    void capPerAddress() {
        for (int i = 0; i < 3; i++) {
            limiter.check("1.1.1.1");
            clock.advance(Duration.ofMinutes(10));
        }
        assertThrows(ConflictException.class, () -> limiter.check("1.1.1.1"));
        limiter.check("2.2.2.2");

        // The first request leaves the window 60 minutes after it was made.
        clock.advance(Duration.ofMinutes(31));
        limiter.check("1.1.1.1");
        assertThrows(ConflictException.class, () -> limiter.check("1.1.1.1"));
    }

    @Test
    @DisplayName("the address is the proxy's last X-Forwarded-For hop, not one the client wrote")
    void clientIp() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        assertEquals("10.0.0.1", OtpIpLimiter.clientIp(request));

        request.addHeader("X-Forwarded-For", "6.6.6.6, 203.0.113.7");
        assertEquals("203.0.113.7", OtpIpLimiter.clientIp(request));
    }
}
