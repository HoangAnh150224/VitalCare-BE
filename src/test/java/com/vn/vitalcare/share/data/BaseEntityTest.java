package com.vn.vitalcare.share.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vn.vitalcare.entity.Clinic;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;

/**
 * Proves the audit stamps are actually wired, not merely annotated.
 *
 * <p>Three separate pieces have to agree before a single column gets written:
 * the annotations on {@link BaseEntity}, the
 * {@code AuditingEntityListener} registered on it, and {@code @EnableJpaAuditing}
 * with its {@code AuditorAware}. Miss any one and persisting still succeeds —
 * the columns just come out null, and {@code created_at NOT NULL} turns that
 * into a constraint violation at the first write of the first feature to use
 * one of these tables. Nothing else in the suite would notice.
 *
 * <p>{@code Clinic} is the subject because it is the shape a base class is
 * least likely to fit: a caller-supplied {@code UUID} key in a column that is
 * not called {@code id}.
 *
 * <p>{@code @Transactional} so the row rolls back — this runs against the
 * development database.
 */
@SpringBootTest
@Transactional
class BaseEntityTest {

    private static final long ACTOR_ID = 1L;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void signIn() {
        // CurrentUser reads the subject off a validated token, so the auditor
        // is only resolvable with one in the context.
        Jwt token = Jwt.withTokenValue("test")
                .header("alg", "none")
                .subject(String.valueOf(ACTOR_ID))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(token));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("an insert stamps when and by whom, and starts the version at zero")
    void insertIsStamped() {
        Clinic clinic = persistClinic();

        assertNotNull(clinic.getCreatedAt(), "created_at is NOT NULL; a null here fails the insert");
        assertEquals(ACTOR_ID, clinic.getCreatedById());
        assertEquals(0, clinic.getVersion());
        assertNull(clinic.getDeletedAt());
        assertFalse(clinic.isDeleted());
    }

    @Test
    @DisplayName("an update moves the modified stamps and leaves the created ones alone")
    void updateLeavesCreationAlone() {
        Clinic clinic = persistClinic();
        Instant createdAt = clinic.getCreatedAt();

        clinic.setClinicName("Renamed");
        entityManager.flush();

        assertEquals(1, clinic.getVersion(), "@Version did not increment");
        assertEquals(ACTOR_ID, clinic.getUpdatedById());
        assertNotNull(clinic.getUpdatedAt());
        // updatable = false is what enforces this; without it an UPDATE would
        // happily rewrite who created the row.
        assertEquals(createdAt, clinic.getCreatedAt());
        assertEquals(ACTOR_ID, clinic.getCreatedById());
    }

    @Test
    @DisplayName("an unauthenticated write leaves the author empty rather than inventing one")
    void writeWithNoAuthorIsAllowed() {
        SecurityContextHolder.clearContext();

        Clinic clinic = persistClinic();

        assertNotNull(clinic.getCreatedAt(), "the date does not depend on there being an author");
        assertNull(clinic.getCreatedById(), "a seed or a scheduled job has no author to record");
    }

    @Test
    @DisplayName("marking deleted records who and when, and keeps the first deletion")
    void deletionIsRecordedOnce() {
        Clinic clinic = persistClinic();
        Instant first = Instant.parse("2026-01-01T00:00:00Z");

        clinic.markDeleted(ACTOR_ID, first);
        clinic.markDeleted(99L, first.plusSeconds(86400));

        assertTrue(clinic.isDeleted());
        // Idempotent on purpose: the deletion that happened is the first one.
        assertEquals(first, clinic.getDeletedAt());
        assertEquals(ACTOR_ID, clinic.getDeletedById());

        clinic.restore();
        assertFalse(clinic.isDeleted());
        assertNull(clinic.getDeletedById());
    }

    private Clinic persistClinic() {
        Clinic clinic = new Clinic();
        clinic.setClinicId(UUID.randomUUID());
        clinic.setClinicName("Audit Fixture");
        entityManager.persist(clinic);
        // Stamps are applied on the pre-persist event, so nothing is readable
        // until the insert is actually sent.
        entityManager.flush();
        return clinic;
    }
}
