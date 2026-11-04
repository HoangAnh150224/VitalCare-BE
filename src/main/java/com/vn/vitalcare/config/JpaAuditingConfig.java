package com.vn.vitalcare.config;

import com.vn.vitalcare.share.data.BaseEntity;
import com.vn.vitalcare.share.security.CurrentUser;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Turns on the stamping behind {@link BaseEntity}.
 *
 * <p>Without {@code @EnableJpaAuditing} the annotations on that class are
 * inert: the listener is registered but has no handler, so every row would be
 * written with a null {@code created_at} and fail the NOT NULL constraint. The
 * two halves only work together.
 */
@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
public class JpaAuditingConfig {

    /**
     * Who the current write belongs to, as a {@code users.id}.
     *
     * <p>Reads the authenticated subject out of the access token rather than
     * the database, so stamping a row costs nothing — the same reason
     * authorising a request does not query.
     *
     * <p>Empty is a legitimate answer, not a failure: a Liquibase seed, a
     * scheduled job or anything running outside a request has no author, and
     * the author columns are nullable precisely so those writes can say so
     * instead of inventing a user.
     */
    @Bean
    public AuditorAware<Long> auditorAware() {
        return CurrentUser::id;
    }
}
