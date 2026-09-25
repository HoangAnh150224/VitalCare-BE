package com.vn.vitalcare.identity.rowlevel.service;

import com.vn.vitalcare.share.security.rowlevel.FieldDescriptor;
import com.vn.vitalcare.share.security.rowlevel.RowLevelContextProvider;
import com.vn.vitalcare.share.security.rowlevel.RowLevelPolicySet;
import com.vn.vitalcare.share.security.rowlevel.RowLevelResource;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * Which resources are under row-level management, and what a policy on each of
 * them may say.
 *
 * <p>Built once from the {@link RowLevelPolicySet} beans the domains declare.
 * Nothing here reads the database and nothing changes after startup: this is the
 * source-code half of the design, and it is deliberately immutable so that the
 * runtime half — which does change — has something fixed to be checked against.
 *
 * <p>A resource has two halves that must agree: the {@link RowLevelResource}
 * annotation on the entity, and a policy set naming the same resource. Neither
 * is any use alone, and {@link RowLevelMetadataValidator} refuses to start the
 * application when one is missing.
 */
@Component
public class RowLevelRegistry {

    private final Map<String, Managed> byResource = new TreeMap<>();
    private final Map<Class<?>, Managed> byEntity = new LinkedHashMap<>();
    private final Map<String, RowLevelContextProvider> context = new TreeMap<>();

    public RowLevelRegistry(List<RowLevelPolicySet<?>> policySets,
                            List<RowLevelContextProvider> contextProviders) {

        for (RowLevelPolicySet<?> policySet : policySets) {
            Managed managed = Managed.of(policySet);

            Managed clash = byResource.putIfAbsent(managed.resource(), managed);
            if (clash != null) {
                throw new IllegalStateException(
                        "Two policy sets both claim the resource \"%s\": %s and %s".formatted(
                                managed.resource(),
                                clash.policySet().getClass().getName(),
                                policySet.getClass().getName()));
            }
            byEntity.put(managed.entityClass(), managed);
        }

        for (RowLevelContextProvider provider : contextProviders) {
            RowLevelContextProvider clash = context.putIfAbsent(provider.key(), provider);
            if (clash != null) {
                // A duplicate key is not a merge, it is a coin toss over which
                // bean answers -- and the two would answer differently or there
                // would be no second bean.
                throw new IllegalStateException(
                        "Two context providers both claim the key \"%s\": %s and %s".formatted(
                                provider.key(),
                                clash.getClass().getName(),
                                provider.getClass().getName()));
            }
        }
    }

    public Optional<Managed> byResource(String resource) {
        return Optional.ofNullable(byResource.get(resource));
    }

    /**
     * The managed resource an entity belongs to, if any.
     *
     * <p>Walks up the class hierarchy, because Hibernate hands out proxies that
     * are subclasses of the entity. Looking only at {@code getClass()} would
     * make every lazily loaded instance look unmanaged — a failure that opens
     * rather than closes.
     */
    public Optional<Managed> byEntity(Class<?> entityClass) {
        for (Class<?> type = entityClass; type != null && type != Object.class; type = type.getSuperclass()) {
            Managed managed = byEntity.get(type);
            if (managed != null) {
                return Optional.of(managed);
            }
        }
        return Optional.empty();
    }

    public Collection<Managed> all() {
        return byResource.values();
    }

    public Optional<RowLevelContextProvider> contextProvider(String key) {
        return Optional.ofNullable(context.get(key));
    }

    public Collection<RowLevelContextProvider> contextProviders() {
        return context.values();
    }

    /** One resource, with its fields indexed the way a policy writes them. */
    public record Managed(
            String resource,
            Class<?> entityClass,
            RowLevelPolicySet<?> policySet,
            Map<String, FieldDescriptor> fields) {

        static Managed of(RowLevelPolicySet<?> policySet) {
            Class<?> entityClass = policySet.entityClass();
            RowLevelResource annotation = entityClass.getAnnotation(RowLevelResource.class);

            if (annotation == null) {
                throw new IllegalStateException(
                        "%s declares policies for %s, which is not annotated @RowLevelResource".formatted(
                                policySet.getClass().getName(), entityClass.getName()));
            }
            if (!annotation.value().equals(policySet.resource())) {
                // Two names for one thing is the drift this design has no
                // lookup table precisely in order to avoid.
                throw new IllegalStateException(
                        "%s is annotated @RowLevelResource(\"%s\") but %s says the resource is \"%s\"".formatted(
                                entityClass.getName(), annotation.value(),
                                policySet.getClass().getName(), policySet.resource()));
            }
            if (policySet.defaultScope() == null) {
                throw new IllegalStateException(
                        "%s must declare a defaultScope".formatted(policySet.getClass().getName()));
            }

            Map<String, FieldDescriptor> fields = new LinkedHashMap<>();
            for (FieldDescriptor field : policySet.fields()) {
                FieldDescriptor clash = fields.putIfAbsent(field.policyPath(), field);
                if (clash != null) {
                    throw new IllegalStateException(
                            "%s declares the field \"%s\" twice".formatted(
                                    policySet.getClass().getName(), field.policyPath()));
                }
            }
            return new Managed(policySet.resource(), entityClass, policySet, Map.copyOf(fields));
        }

        @SuppressWarnings("unchecked")
        public <T> RowLevelPolicySet<T> typedPolicySet() {
            return (RowLevelPolicySet<T>) policySet;
        }
    }
}
