package com.vn.vitalcare.share.data;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T11 — the unscoped read paths must not exist.
 *
 * <p>The design's sixth principle is that you cannot forget something that is
 * not there to be called. Any repository extending {@link SecuredRepository}
 * inherits {@code Repository} and {@code JpaSpecificationExecutor} and
 * deliberately not {@code JpaRepository} — so {@code findById},
 * {@code findAll()} and {@code findAllById} are absent, and
 * {@code repository.findById(1L)} does not compile.
 *
 * <p>Tested against a bare fixture interface rather than a real domain's
 * repository, since this property belongs to {@link SecuredRepository} itself
 * and does not need a managed entity behind it — no Spring context is
 * involved, only reflection over the interface's method set.
 *
 * <p>Asserted by reflection rather than by a compilation harness. "This does
 * not compile" is the property, and a compile-testing library would be a
 * dependency added to prove one fact; the interface's method set is the same
 * fact, and this fails just as loudly the day somebody changes the
 * {@code extends} clause back.
 *
 * <p>What it does <em>not</em> claim: that a scope is correct. Only that no
 * method exists which could skip one.
 */
class SecuredRepositoryApiTest {

    /** A fixture repository, existing only so its inherited method set can be inspected. */
    private interface SampleSecuredRepository extends SecuredRepository<Object, Long> {
    }

    /**
     * The inherited methods that read rows without being handed a scope.
     *
     * <p>{@code findAll()} is spelled by arity below rather than by name,
     * because {@code findAll(Specification)} is exactly the method that must
     * survive.
     */
    private static final List<String> FORBIDDEN = List.of(
            "findById", "findAllById", "getReferenceById", "getById", "getOne", "existsById");

    @Test
    @DisplayName("T11: a row-level repository has no unscoped read method to call by mistake")
    void unscopedReadsDoNotExist() {
        List<String> names = Arrays.stream(SampleSecuredRepository.class.getMethods())
                .map(Method::getName)
                .toList();

        for (String forbidden : FORBIDDEN) {
            assertFalse(names.contains(forbidden),
                    "SecuredRepository exposes %s, which reads rows without a scope".formatted(forbidden));
        }
    }

    @Test
    @DisplayName("T11: findAll() with no argument does not exist, but findAll(Specification) does")
    void onlyTheScopedFindAllSurvives() {
        boolean unscoped = Arrays.stream(SampleSecuredRepository.class.getMethods())
                .anyMatch(method -> method.getName().equals("findAll") && method.getParameterCount() == 0);
        assertFalse(unscoped, "SecuredRepository exposes findAll(), which reads every row");

        boolean scoped = Arrays.stream(SampleSecuredRepository.class.getMethods())
                .anyMatch(method -> method.getName().equals("findAll")
                        && method.getParameterCount() >= 1
                        && method.getParameterTypes()[0].getSimpleName().equals("Specification"));
        assertTrue(scoped, "SecuredRepository must still be able to read through a Specification");
    }

    @Test
    @DisplayName("count() is absent too — a total is a read, and an unscoped total leaks the size of the table")
    void unscopedCountDoesNotExist() {
        boolean unscoped = Arrays.stream(SampleSecuredRepository.class.getMethods())
                .anyMatch(method -> method.getName().equals("count") && method.getParameterCount() == 0);
        assertFalse(unscoped, "SecuredRepository exposes count(), which counts rows the caller cannot see");
    }

    @Test
    @DisplayName("The three write methods are still there, or the repository would be useless")
    void writesAreStillAvailable() {
        List<String> names = Arrays.stream(SampleSecuredRepository.class.getMethods())
                .map(Method::getName)
                .toList();

        assertTrue(names.contains("persist"));
        assertTrue(names.contains("remove"));
        // flush() is on the create path: the WITH CHECK has to run against a row
        // that already has its identity and its @PrePersist stamps.
        assertTrue(names.contains("flush"));
    }
}
