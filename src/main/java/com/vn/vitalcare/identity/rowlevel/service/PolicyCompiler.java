package com.vn.vitalcare.identity.rowlevel.service;

import com.vn.vitalcare.share.security.rowlevel.Action;
import com.vn.vitalcare.share.security.rowlevel.FieldDescriptor;
import com.vn.vitalcare.share.security.rowlevel.Op;
import com.vn.vitalcare.share.security.rowlevel.PolicyValidationException;
import com.vn.vitalcare.share.security.rowlevel.RowLevelContextProvider;
import com.vn.vitalcare.share.security.rowlevel.ScopeTree;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Turns an authored {@link ScopeTree} into a {@link ScopePlan}: once, when the
 * policy is saved or when the cache loads it — never per request and never per
 * row.
 *
 * <p>Four things happen here, and the order matters.
 *
 * <ol>
 *   <li><b>{@code between} is expanded</b> to {@code all[gte, lte]}, so nothing
 *       downstream has a two-operand operator to special-case, and so its
 *       negation falls out of De Morgan as {@code any[lt, gt]} rather than
 *       needing a rule of its own.</li>
 *   <li><b>{@code not} is pushed to the leaves</b> and absorbed into their
 *       operators. See {@link ScopePlan} for why this is a security property
 *       rather than a tidiness one.</li>
 *   <li><b>Fields are resolved</b> against the closed set the domain declared,
 *       and the operator is checked against the field's type.</li>
 *   <li><b>Literals are coerced</b> to the field's type, and context keys are
 *       bound to their provider with the types checked. A policy that cannot
 *       possibly match is refused at the moment it is written, not discovered
 *       later as an empty screen.</li>
 * </ol>
 */
@Component
public class PolicyCompiler {

    private final RowLevelRegistry registry;

    public PolicyCompiler(RowLevelRegistry registry) {
        this.registry = registry;
    }

    /**
     * Compiles one policy's tree.
     *
     * @param action the policy's action, which decides whether
     *               {@link com.vn.vitalcare.share.security.rowlevel.RowLevelPolicySet#notCheckSafe()}
     *               applies
     * @throws PolicyValidationException with a pointer to the offending node
     */
    public ScopePlan compile(RowLevelRegistry.Managed resource, Action action, ScopeTree tree) {
        return node(tree, resource, action, false, false, "scope");
    }

    /**
     * Compiles the {@code WITH CHECK} clause — the condition the row has to
     * satisfy <em>after</em> a write.
     *
     * <p>Only this clause enforces
     * {@link com.vn.vitalcare.share.security.rowlevel.RowLevelPolicySet#notCheckSafe()},
     * and that is the point of separating them. A field whose value only exists
     * once the row has been flushed is fine in a {@code USING} clause: that
     * clause is evaluated against a row already loaded from the database, which
     * necessarily has one. It is only the check on a freshly created row that
     * would be reading a value that is not there yet.
     */
    public ScopePlan compileCheck(RowLevelRegistry.Managed resource, Action action, ScopeTree tree) {
        return node(tree, resource, action, false, true, "checkScope");
    }

    /**
     * One node.
     *
     * @param negated whether an odd number of {@code not}s encloses this node.
     *                Carrying it down as a flag <em>is</em> the normalisation:
     *                the negation never becomes a node of its own, so it cannot
     *                survive into the plan
     */
    private ScopePlan node(
            ScopeTree tree,
            RowLevelRegistry.Managed resource,
            Action action,
            boolean negated,
            boolean checkClause,
            String at) {

        return switch (tree) {
            case ScopeTree.Not not -> node(not.node(), resource, action, !negated, checkClause, at + ".not");

            // De Morgan: under a negation, all becomes any and any becomes all.
            case ScopeTree.All all -> {
                List<ScopePlan> nodes = children(all.nodes(), resource, action, negated, checkClause, at + ".all");
                yield negated ? new ScopePlan.Any(nodes) : new ScopePlan.All(nodes);
            }
            case ScopeTree.Any any -> {
                List<ScopePlan> nodes = children(any.nodes(), resource, action, negated, checkClause, at + ".any");
                yield negated ? new ScopePlan.All(nodes) : new ScopePlan.Any(nodes);
            }

            case ScopeTree.Leaf leaf -> leaf(leaf, resource, action, negated, checkClause, at);
        };
    }

    private List<ScopePlan> children(
            List<ScopeTree> nodes,
            RowLevelRegistry.Managed resource,
            Action action,
            boolean negated,
            boolean checkClause,
            String at) {

        List<ScopePlan> compiled = new ArrayList<>(nodes.size());
        for (int i = 0; i < nodes.size(); i++) {
            compiled.add(node(nodes.get(i), resource, action, negated, checkClause, "%s[%d]".formatted(at, i)));
        }
        return compiled;
    }

    private ScopePlan leaf(
            ScopeTree.Leaf leaf,
            RowLevelRegistry.Managed resource,
            Action action,
            boolean negated,
            boolean checkClause,
            String at) {

        FieldDescriptor field = resource.fields().get(leaf.field());
        if (field == null) {
            throw new PolicyValidationException(at + ".field", """
                    "%s" is not a field %s policies may use. Available: %s"""
                    .formatted(leaf.field(), resource.resource(), String.join(", ", resource.fields().keySet())));
        }

        // Refused here rather than at the moment a create is rejected for no
        // visible reason. The value of these fields only exists after the row
        // has been flushed, so a write policy naming one turns down every
        // insert -- and would look like a bug in the create form, not like the
        // policy it is.
        if (checkClause && action == Action.WRITE
                && resource.policySet().notCheckSafe().contains(field.attribute())) {
            throw new PolicyValidationException(at + ".field", """
                    "%s" cannot be used in a write policy: its value only exists once the row has been \
                    saved, so the check would refuse every new record"""
                    .formatted(leaf.field()));
        }

        if (!field.operators().contains(leaf.op())) {
            throw new PolicyValidationException(at + ".op", """
                    "%s" does not support %s. Available: %s"""
                    .formatted(leaf.field(), leaf.op().code(), operatorNames(field)));
        }

        // Expanded before the negation is applied, so that not(between) becomes
        // any[lt, gt] through De Morgan rather than needing a rule of its own.
        if (leaf.op() == Op.BETWEEN) {
            return between(leaf, field, negated, at);
        }

        Op op = negated ? leaf.op().negate() : leaf.op();
        return new ScopePlan.Leaf(field, op, operand(leaf.value(), field, op, at + ".value"));
    }

    private ScopePlan between(ScopeTree.Leaf leaf, FieldDescriptor field, boolean negated, String at) {
        List<?> bounds = bounds(leaf.value(), at + ".value");

        ScopePlan lower = new ScopePlan.Leaf(
                field,
                negated ? Op.LT : Op.GTE,
                new ScopePlan.Operand.Literal(coerce(bounds.get(0), field, at + ".value[0]")));
        ScopePlan upper = new ScopePlan.Leaf(
                field,
                negated ? Op.GT : Op.LTE,
                new ScopePlan.Operand.Literal(coerce(bounds.get(1), field, at + ".value[1]")));

        return negated ? new ScopePlan.Any(List.of(lower, upper)) : new ScopePlan.All(List.of(lower, upper));
    }

    private List<?> bounds(ScopeTree.Value value, String at) {
        if (!(value instanceof ScopeTree.Value.Lit lit) || !(lit.value() instanceof List<?> list)) {
            throw new PolicyValidationException(at, "between needs two literal bounds");
        }
        if (list.size() != 2) {
            throw new PolicyValidationException(at, "between needs exactly two bounds, not " + list.size());
        }
        return list;
    }

    private ScopePlan.Operand operand(ScopeTree.Value value, FieldDescriptor field, Op op, String at) {
        if (op.isNullary()) {
            return new ScopePlan.Operand.None();
        }
        if (value == null) {
            throw new PolicyValidationException(at, "%s needs a value".formatted(op.code()));
        }

        return switch (value) {
            case ScopeTree.Value.Ctx ctx -> context(ctx, field, op, at);
            case ScopeTree.Value.Lit lit -> literal(lit, field, op, at);
        };
    }

    private ScopePlan.Operand context(ScopeTree.Value.Ctx ctx, FieldDescriptor field, Op op, String at) {
        RowLevelContextProvider provider = registry.contextProvider(ctx.key())
                .orElseThrow(() -> new PolicyValidationException(at + ".ctx", """
                        "%s" is not a registered context key. Available: %s"""
                        .formatted(ctx.key(), String.join(", ", keys()))));

        // Checked when the policy is saved, which is the only moment somebody is
        // present to be told. At runtime a mismatch would simply never match,
        // and never matching looks exactly like a correct policy over data that
        // happens to be empty.
        if (!compatible(provider.type(), field.comparableType())) {
            throw new PolicyValidationException(at + ".ctx", """
                    "%s" yields %s, which cannot be compared with %s (%s)"""
                    .formatted(ctx.key(), provider.type().getSimpleName(),
                            field.policyPath(), field.comparableType().getSimpleName()));
        }
        if (op.isSetValued() != provider.isCollection()) {
            throw new PolicyValidationException(at + ".ctx", provider.isCollection()
                    ? "\"%s\" is a set, so it needs in or notIn".formatted(ctx.key())
                    : "\"%s\" is a single value, so it cannot be used with %s".formatted(ctx.key(), op.code()));
        }
        return new ScopePlan.Operand.Context(provider);
    }

    private ScopePlan.Operand literal(ScopeTree.Value.Lit lit, FieldDescriptor field, Op op, String at) {
        if (op.isSetValued()) {
            if (!(lit.value() instanceof List<?> list)) {
                throw new PolicyValidationException(at, "%s needs a list of values".formatted(op.code()));
            }
            List<Object> values = new ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) {
                values.add(coerce(list.get(i), field, "%s[%d]".formatted(at, i)));
            }
            // An empty set is legal and means what the three-valued rules say it
            // means: nothing for `in`, everything for `notIn`. A context
            // provider that answers "manages no departments" has to be able to
            // say so without it being a syntax error.
            return new ScopePlan.Operand.Literals(List.copyOf(values));
        }
        if (lit.value() instanceof List<?>) {
            throw new PolicyValidationException(at, "%s takes a single value".formatted(op.code()));
        }
        return new ScopePlan.Operand.Literal(coerce(lit.value(), field, at));
    }

    /**
     * A JSON literal as the field's own type.
     *
     * <p>Done once here rather than per row, and it is also the check: a value
     * that will not convert is a policy that could never have matched, and
     * saying so now is worth far more than saying nothing later.
     */
    private Object coerce(Object value, FieldDescriptor field, String at) {
        Class<?> type = field.comparableType();
        if (value == null) {
            throw new PolicyValidationException(at, "a literal cannot be null; use isNull instead");
        }

        try {
            if (type == Long.class || type == long.class) {
                return value instanceof Number number ? number.longValue() : Long.valueOf(value.toString().trim());
            }
            if (type == Integer.class || type == int.class) {
                return value instanceof Number number ? number.intValue() : Integer.valueOf(value.toString().trim());
            }
            if (type == Double.class || type == double.class) {
                return value instanceof Number number ? number.doubleValue() : Double.valueOf(value.toString().trim());
            }
            if (type == BigDecimal.class) {
                return value instanceof BigDecimal decimal ? decimal : new BigDecimal(value.toString().trim());
            }
            if (type == Boolean.class || type == boolean.class) {
                return value instanceof Boolean bool ? bool : Boolean.valueOf(value.toString().trim());
            }
            if (type == String.class) {
                return value.toString();
            }
            if (type.isEnum()) {
                return enumValue(type, value.toString().trim(), at);
            }
            if (type == LocalDate.class) {
                return LocalDate.parse(value.toString().trim());
            }
            if (type == LocalDateTime.class) {
                return LocalDateTime.parse(value.toString().trim());
            }
            if (type == Instant.class) {
                return Instant.parse(value.toString().trim());
            }
        } catch (NumberFormatException | DateTimeParseException e) {
            throw new PolicyValidationException(at, """
                    "%s" is not a valid %s for %s"""
                    .formatted(value, type.getSimpleName(), field.policyPath()));
        }

        throw new PolicyValidationException(at, """
                %s has the type %s, which policies cannot compare against"""
                .formatted(field.policyPath(), type.getSimpleName()));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object enumValue(Class<?> type, String value, String at) {
        try {
            return Enum.valueOf((Class<? extends Enum>) type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            List<String> names = new ArrayList<>();
            for (Object constant : type.getEnumConstants()) {
                names.add(((Enum<?>) constant).name());
            }
            throw new PolicyValidationException(at, """
                    "%s" is not one of %s""".formatted(value, String.join(", ", names)));
        }
    }

    /** Numeric widths are treated as compatible; everything else has to match. */
    private boolean compatible(Class<?> provided, Class<?> expected) {
        if (expected.isAssignableFrom(provided) || provided.isAssignableFrom(expected)) {
            return true;
        }
        return Number.class.isAssignableFrom(provided) && Number.class.isAssignableFrom(expected);
    }

    private List<String> keys() {
        return registry.contextProviders().stream().map(RowLevelContextProvider::key).toList();
    }

    private static String operatorNames(FieldDescriptor field) {
        return field.operators().stream().map(Op::code).sorted().reduce((a, b) -> a + ", " + b).orElse("none");
    }
}
