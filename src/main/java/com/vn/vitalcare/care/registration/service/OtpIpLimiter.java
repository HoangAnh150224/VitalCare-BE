package com.vn.vitalcare.care.registration.service;

import com.vn.vitalcare.share.exception.ConflictException;
import com.vn.vitalcare.share.web.IpWindowLimiter;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
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
@Component
public class OtpIpLimiter {

    private final IpWindowLimiter limiter;

    public OtpIpLimiter(@Value("${app.otp.max-per-ip-per-hour:10}") int maxPerHour, Clock clock) {
        this.limiter = new IpWindowLimiter(Duration.ofHours(1), maxPerHour,
                "Too many codes have been requested from this network; try again later", clock);
    }

    /**
     * Counts one request from this address.
     *
     * @throws ConflictException when the address has used up its hour
     */
    public void check(String ip) {
        limiter.check(ip);
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
