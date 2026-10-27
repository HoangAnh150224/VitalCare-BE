package com.vn.vitalcare.care.registration.service;

import com.vn.vitalcare.share.exception.ConflictException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Caps how many one-time codes one client address may ask for per rolling
 * hour, whatever numbers it asks for.
 *
 * <p>The per-number limits in {@link PhoneCodes} stop one phone being flooded;
 * this stops one client walking a list of numbers, each SMS of which is paid
 * for. Every request counts, with or without an account behind the number, so
 * the cap says nothing about which numbers have one.
 */
// ponytail: in memory, per instance; move to the DB or Redis if the API runs on more than one instance.
@Component
public class OtpIpLimiter {

    private static final Duration WINDOW = Duration.ofHours(1);
    /** Past this many tracked addresses, the ones idle for a whole window are dropped. */
    private static final int PRUNE_ABOVE = 10_000;

    private final int maxPerHour;
    private final Clock clock;
    /** Each address's request times within the window, oldest first; lists are never mutated. */
    private final Map<String, List<Instant>> hits = new ConcurrentHashMap<>();

    public OtpIpLimiter(@Value("${app.otp.max-per-ip-per-hour:10}") int maxPerHour, Clock clock) {
        this.maxPerHour = maxPerHour;
        this.clock = clock;
    }

    /**
     * Counts one request from this address.
     *
     * @throws ConflictException when the address has used up its hour
     */
    public void check(String ip) {
        Instant now = clock.instant();
        Instant cutoff = now.minus(WINDOW);
        if (hits.size() > PRUNE_ABOVE) {
            hits.values().removeIf(times -> times.getLast().isBefore(cutoff));
        }
        // compute() runs atomically per key; throwing inside leaves the entry as it was.
        hits.compute(ip, (key, times) -> {
            List<Instant> recent = new ArrayList<>();
            if (times != null) {
                times.stream().filter(time -> !time.isBefore(cutoff)).forEach(recent::add);
            }
            if (recent.size() >= maxPerHour) {
                throw new ConflictException("Too many codes have been requested from this network; try again later");
            }
            recent.add(now);
            return List.copyOf(recent);
        });
    }

    /**
     * The client address: the last hop of X-Forwarded-For behind a proxy
     * (Railway), else the connection's own address.
     *
     * <p>The last hop, not the first: it is the one the nearest proxy wrote.
     * Anything before it the client may have typed itself, and a limit keyed
     * on that would be a limit the client picks.
     */
    public static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String[] hops = forwarded.split(",");
            return hops[hops.length - 1].trim();
        }
        return request.getRemoteAddr();
    }
}
