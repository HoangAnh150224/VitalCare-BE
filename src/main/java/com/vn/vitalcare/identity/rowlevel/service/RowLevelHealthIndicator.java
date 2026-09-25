package com.vn.vitalcare.identity.rowlevel.service;

import java.util.List;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reports {@code DOWN} while any policy is quarantined.
 *
 * <p>Quarantine is the deliberate choice not to stop the node over one broken
 * row — but a policy that is not applying is a security control that is not
 * applying, and that cannot be allowed to be invisible. Refusing to boot turns a
 * configuration mistake into an outage; saying nothing turns it into a silent
 * one. Failing the health check is the third option: the node keeps serving, and
 * whoever watches it finds out within a minute.
 */
@Component("rowLevelSecurity")
public class RowLevelHealthIndicator implements HealthIndicator {

    private final RowLevelPolicyCache cache;

    public RowLevelHealthIndicator(RowLevelPolicyCache cache) {
        this.cache = cache;
    }

    @Override
    public Health health() {
        List<RowLevelPolicyCache.Quarantined> quarantined = cache.quarantined();
        if (quarantined.isEmpty()) {
            return Health.up().withDetail("generation", cache.generation()).build();
        }
        return Health.down()
                .withDetail("generation", cache.generation())
                .withDetail("quarantined", quarantined.stream()
                        .map(policy -> "#%d %s %s:%s — %s".formatted(
                                policy.policyId(), policy.kind(), policy.resource(),
                                policy.action(), policy.reason()))
                        .toList())
                .build();
    }
}
