package com.vn.vitalcare.identity.rowlevel.service;

import com.vn.vitalcare.share.security.rowlevel.RowLevelContextProvider;
import com.vn.vitalcare.share.security.rowlevel.RowLevelPrincipal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The context keys every application gets.
 *
 * <p>Each is one bean volunteering one key, which is the whole extension
 * mechanism: an application-specific key is another bean of the same shape, and
 * nothing in the engine changes to accommodate it.
 *
 * <p>None of them queries anything. Everything the account-shaped keys need is
 * already on {@link RowLevelPrincipal}, which came out of the authority cache —
 * so a scope costs no SQL at all, which is the budget the whole design is built
 * to.
 */
@Configuration
public class RowLevelContextProviders {

    @Bean
    RowLevelContextProvider currentUserId() {
        return provider("user.id", Long.class, principal -> principal.userId());
    }

    @Bean
    RowLevelContextProvider currentOrganizationId() {
        return provider("user.organizationId", Long.class, RowLevelPrincipal::organizationId);
    }

    @Bean
    RowLevelContextProvider currentDepartmentId() {
        return provider("user.departmentId", Long.class, RowLevelPrincipal::departmentId);
    }

    /** A set, so it is usable only with {@code in} and {@code notIn}. */
    @Bean
    RowLevelContextProvider currentRoleCodes() {
        return new RowLevelContextProvider() {
            @Override
            public String key() {
                return "user.roleCodes";
            }

            @Override
            public Class<?> type() {
                return String.class;
            }

            @Override
            public boolean isCollection() {
                return true;
            }

            @Override
            public Object resolve(RowLevelPrincipal principal) {
                return Set.copyOf(principal.roleCodes());
            }
        };
    }

    /**
     * Today, on the server.
     *
     * <p>Resolved here rather than sent by the client: a browser in another
     * timezone must not be able to disagree with a security policy about what
     * day it is.
     */
    @Bean
    RowLevelContextProvider today() {
        return provider("today", LocalDate.class, principal -> LocalDate.now());
    }

    @Bean
    RowLevelContextProvider now() {
        return provider("now", Instant.class, principal -> Instant.now());
    }

    private static RowLevelContextProvider provider(
            String key, Class<?> type, java.util.function.Function<RowLevelPrincipal, Object> resolve) {

        return new RowLevelContextProvider() {
            @Override
            public String key() {
                return key;
            }

            @Override
            public Class<?> type() {
                return type;
            }

            @Override
            public Object resolve(RowLevelPrincipal principal) {
                return resolve.apply(principal);
            }
        };
    }
}
