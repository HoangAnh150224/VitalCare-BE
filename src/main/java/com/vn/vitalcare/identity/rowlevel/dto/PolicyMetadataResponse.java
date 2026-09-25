package com.vn.vitalcare.identity.rowlevel.dto;

import java.util.List;

/**
 * Everything the condition builder needs in order to offer only valid choices.
 *
 * <p>This is why the admin screen has <b>no free-text expression box</b>. Every
 * field, every operator and every context key is a dropdown built from this
 * payload, so a policy that could not compile is one the UI will not let anybody
 * write.
 *
 * @param designTime the policies declared in source for this resource, reported
 *                   read-only. Without them an administrator would see half of
 *                   what is in force and take it for all of it
 */
public record PolicyMetadataResponse(
        String resource,
        String defaultScope,
        List<Field> fields,
        List<Context> context,
        List<String> notCheckSafe,
        List<DesignTime> designTime) {

    /**
     * @param type        a wire-level type name — {@code enum}, {@code long},
     *                    {@code date} — rather than a Java class, because the
     *                    builder renders an input from it and has no business
     *                    knowing about {@code java.time.LocalDate}
     * @param values      the enum constants, so the value input is a dropdown too
     * @param warnNotNull whether putting a {@code not} on this field needs the
     *                    three-valued-logic warning
     */
    public record Field(
            String path,
            String type,
            boolean nullable,
            List<String> values,
            List<String> operators,
            boolean warnNotNull) {
    }

    public record Context(String key, String type, boolean collection) {
    }

    /** A source-declared policy: visible, explained, and not editable from here. */
    public record DesignTime(String kind, String action, String role, String name) {
    }
}
