package com.vn.vitalcare.share.web;

import com.vn.vitalcare.share.exception.ConflictException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * At most {@code max} requests per key (a client address, usually) in any
 * rolling {@code window}. What the endpoints open to everybody use instead of
 * a captcha: one-time codes, guest chat.
 *
 * <p>Not a bean: each user builds its own, with its own window, cap and
 * message, so one endpoint's traffic never counts against another's.
 */
// ponytail: in memory, per instance; move to the DB or Redis if the API runs on more than one instance.
public final class IpWindowLimiter {

    /** Past this many tracked keys, the ones idle for a whole window are dropped. */
    private static final int PRUNE_ABOVE = 10_000;

    private final Duration window;
    private final int max;
    private final String refusal;
    private final Clock clock;
    /** Each key's request times within the window, oldest first; lists are never mutated. */
    private final Map<String, List<Instant>> hits = new ConcurrentHashMap<>();

    public IpWindowLimiter(Duration window, int max, String refusal, Clock clock) {
        this.window = window;
        this.max = max;
        this.refusal = refusal;
        this.clock = clock;
    }

    /**
     * Counts one request for this key.
     *
     * @throws ConflictException with the refusal message when the key has used up its window
     */
    public void check(String key) {
        Instant now = clock.instant();
        Instant cutoff = now.minus(window);
        if (hits.size() > PRUNE_ABOVE) {
            hits.values().removeIf(times -> times.getLast().isBefore(cutoff));
        }
        // compute() runs atomically per key; throwing inside leaves the entry as it was.
        hits.compute(key, (k, times) -> {
            List<Instant> recent = new ArrayList<>();
            if (times != null) {
                times.stream().filter(time -> !time.isBefore(cutoff)).forEach(recent::add);
            }
            if (recent.size() >= max) {
                throw new ConflictException(refusal);
            }
            recent.add(now);
            return List.copyOf(recent);
        });
    }
}
