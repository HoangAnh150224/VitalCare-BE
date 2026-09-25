package com.vn.vitalcare.identity.rowlevel.service;

import com.vn.vitalcare.share.security.rowlevel.FieldDescriptor;
import jakarta.persistence.EntityManager;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.EntityType;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Checks the source-code half of the design, and refuses to start if any of it
 * is wrong.
 *
 * <p>Two kinds of data, two very different severities, and conflating them is
 * wrong in both directions.
 *
 * <p><b>Metadata is source code</b>, so a mistake in it is a compile-time
 * mistake that happens to be discoverable only at runtime — a field naming a
 * property the entity does not have, two resources with the same name, two
 * context providers claiming one key. It should stop the application exactly as
 * {@code ddl-auto=validate} stops it for a drifted mapping: at startup, not at
 * the first query, and certainly not by quietly matching nothing.
 *
 * <p><b>A runtime policy is data</b>, and it is handled the other way round —
 * refused hard when it is saved, quarantined when it is loaded. See
 * {@link RowLevelPolicyCache} for why refusing to boot over one row of data is
 * a worse answer than disabling it.
 */
@Component
public class RowLevelMetadataValidator {

    private static final Logger log = LoggerFactory.getLogger(RowLevelMetadataValidator.class);

    private final RowLevelRegistry registry;
    private final EntityManager entityManager;

    public RowLevelMetadataValidator(RowLevelRegistry registry, EntityManager entityManager) {
        this.registry = registry;
        this.entityManager = entityManager;
    }

    /**
     * Runs once the context is up, so the JPA metamodel is available to check
     * paths against.
     *
     * <p>Resource-name uniqueness, policy-set-per-entity and context-key
     * uniqueness are already enforced by {@link RowLevelRegistry}'s constructor,
     * which fails earlier still. What is left for here is everything that needs
     * the metamodel.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void validate() {
        List<String> problems = new ArrayList<>();

        for (RowLevelRegistry.Managed managed : registry.all()) {
            EntityType<?> entity = entityManager.getMetamodel().entity(managed.entityClass());

            for (FieldDescriptor field : managed.fields().values()) {
                Attribute<?, ?> attribute;
                try {
                    attribute = entity.getAttribute(field.attribute());
                } catch (IllegalArgumentException e) {
                    problems.add("%s: field \"%s\" does not exist on %s".formatted(
                            managed.resource(), field.attribute(), managed.entityClass().getSimpleName()));
                    continue;
                }

                boolean association = attribute.isAssociation();
                if (association != (field.kind() == FieldDescriptor.Kind.REFERENCE)) {
                    // The kind decides whether the compiler emits an EXISTS or a
                    // comparison on the root. Getting it wrong is not a warning:
                    // one of the two would not compile against the schema at all.
                    problems.add("%s: field \"%s\" is declared %s but is %san association".formatted(
                            managed.resource(), field.attribute(), field.kind(), association ? "" : "not "));
                }
            }

            for (String unsafe : managed.policySet().notCheckSafe()) {
                boolean declared = managed.fields().values().stream()
                        .anyMatch(field -> field.attribute().equals(unsafe));
                if (!declared) {
                    // Not fatal: naming a field that is not policy surface is
                    // harmless, and being over-cautious about a column that may
                    // become one later is the right instinct to leave room for.
                    log.debug("{}: notCheckSafe names \"{}\", which is not a declared policy field",
                            managed.resource(), unsafe);
                }
            }
        }

        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "Row-level metadata does not match the entity model:\n  " + String.join("\n  ", problems));
        }

        log.info("Row-level security is managing {} resource(s): {}",
                registry.all().size(),
                registry.all().stream().map(RowLevelRegistry.Managed::resource).toList());
    }
}
