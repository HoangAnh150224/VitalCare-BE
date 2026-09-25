package com.vn.vitalcare.identity.rowlevel.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vn.vitalcare.identity.user.entity.User;
import com.vn.vitalcare.share.security.rowlevel.Action;
import com.vn.vitalcare.share.security.rowlevel.DefaultScope;
import com.vn.vitalcare.share.security.rowlevel.FieldDescriptor;
import com.vn.vitalcare.share.security.rowlevel.Op;
import com.vn.vitalcare.share.security.rowlevel.PolicyValidationException;
import com.vn.vitalcare.share.security.rowlevel.RowLevelContextProvider;
import com.vn.vitalcare.share.security.rowlevel.RowLevelPolicySet;
import com.vn.vitalcare.share.security.rowlevel.RowLevelPrincipal;
import com.vn.vitalcare.share.security.rowlevel.RowLevelResource;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * T3 — normalisation, and the checks that happen when a policy is saved.
 *
 * <p>No database and no Spring context: the compiler is a pure function of a
 * tree and a resource declaration, and keeping it testable that way is most of
 * why the compiled form is a separate type from the authored one.
 *
 * <p>The fixture below ({@code Widget}) stands in for a real managed resource
 * (dth-framework's own test used {@code tasks}). This port carries no business
 * domain, so a tiny fixture with the same field shapes ({@code status} enum,
 * two to-one references, a nullable date, a not-check-safe timestamp) exercises
 * the same compiler paths without shipping a fake feature into production.
 */
class PolicyCompilerTest {

    /** Stands in for tasks.status; only the constants the tests touch exist. */
    private enum FixtureStatus {
        TODO, IN_PROGRESS, DONE
    }

    /** Stands in for a department reference — no persistence, just a target type. */
    private static final class FixtureDepartment {
        Long id;
    }

    @RowLevelResource("widgets")
    private static final class Widget {
        Long id;
        FixtureStatus status;
        LocalDate dueDate;
        Instant createdAt;
        User assignee;
        FixtureDepartment department;
    }

    private static final class WidgetRowLevelPolicies implements RowLevelPolicySet<Widget> {
        @Override
        public String resource() {
            return "widgets";
        }

        @Override
        public Class<Widget> entityClass() {
            return Widget.class;
        }

        @Override
        public DefaultScope defaultScope() {
            return DefaultScope.FULL;
        }

        @Override
        public List<FieldDescriptor> fields() {
            return List.of(
                    FieldDescriptor.id("id"),
                    FieldDescriptor.column("status", FixtureStatus.class, false),
                    FieldDescriptor.column("dueDate", LocalDate.class),
                    FieldDescriptor.column("createdAt", Instant.class, false),
                    FieldDescriptor.reference("assignee", User.class),
                    FieldDescriptor.reference("department", FixtureDepartment.class));
        }

        @Override
        public Set<String> notCheckSafe() {
            return Set.of("id", "createdAt");
        }
    }

    private RowLevelRegistry registry;
    private PolicyCompiler compiler;
    private ScopeTreeCodec codec;
    private RowLevelRegistry.Managed widgets;

    @BeforeEach
    void setUp() {
        List<RowLevelPolicySet<?>> policySets = List.of(new WidgetRowLevelPolicies());
        List<RowLevelContextProvider> context = List.of(
                provider("user.id", Long.class),
                provider("user.departmentId", Long.class));

        registry = new RowLevelRegistry(policySets, context);
        compiler = new PolicyCompiler(registry);
        codec = new ScopeTreeCodec(JsonMapper.builder().build());
        widgets = registry.byResource("widgets").orElseThrow();
    }

    @Test
    @DisplayName("T3: not disappears from the inner nodes and is absorbed into the leaf operator")
    void notIsPushedToTheLeaves() {
        ScopePlan plan = compile(Action.READ, """
                { "not": { "all": [
                    { "field": "status", "op": "eq", "value": { "lit": "DONE" } },
                    { "field": "department.id", "op": "eq", "value": { "ctx": "user.departmentId" } }
                ]}}""");

        // De Morgan: not(all) became any.
        ScopePlan.Any any = assertInstanceOf(ScopePlan.Any.class, plan);
        assertEquals(2, any.nodes().size());

        // Each operator was replaced by its own negation rather than being
        // wrapped. This is the property that keeps the EXISTS in the SQL
        // compiler two-valued in a way that agrees with the in-memory one --
        // see ScopePlan for the null-foreign-key case it exists to prevent.
        assertEquals(Op.NE, ((ScopePlan.Leaf) any.nodes().get(0)).op());
        assertEquals(Op.NE, ((ScopePlan.Leaf) any.nodes().get(1)).op());
        assertTrue(containsNoNegationNode(plan));
    }

    @Test
    @DisplayName("T3: a double negation cancels rather than accumulating")
    void doubleNegationCancels() {
        ScopePlan plan = compile(Action.READ,
                """
                { "not": { "not": { "field": "status", "op": "eq", "value": { "lit": "TODO" } } } }""");

        ScopePlan.Leaf leaf = assertInstanceOf(ScopePlan.Leaf.class, plan);
        assertEquals(Op.EQ, leaf.op());
    }

    @Test
    @DisplayName("between is expanded, so nothing downstream has a two-operand operator to handle")
    void betweenIsExpanded() {
        ScopePlan plan = compile(Action.READ, """
                { "field": "dueDate", "op": "between", "value": { "lit": ["2026-01-01", "2026-12-31"] } }""");

        ScopePlan.All all = assertInstanceOf(ScopePlan.All.class, plan);
        assertEquals(Op.GTE, ((ScopePlan.Leaf) all.nodes().get(0)).op());
        assertEquals(Op.LTE, ((ScopePlan.Leaf) all.nodes().get(1)).op());
    }

    @Test
    @DisplayName("Negating between falls out of the expansion as any[lt, gt], with no rule of its own")
    void negatedBetweenNeedsNoSpecialCase() {
        ScopePlan plan = compile(Action.READ, """
                { "not": { "field": "dueDate", "op": "between",
                           "value": { "lit": ["2026-01-01", "2026-12-31"] } } }""");

        ScopePlan.Any any = assertInstanceOf(ScopePlan.Any.class, plan);
        assertEquals(Op.LT, ((ScopePlan.Leaf) any.nodes().get(0)).op());
        assertEquals(Op.GT, ((ScopePlan.Leaf) any.nodes().get(1)).op());
    }

    @Test
    @DisplayName("A literal is coerced to the field's own type when the policy is saved, not per row")
    void literalsAreCoercedOnce() {
        ScopePlan plan = compile(Action.READ,
                """
                { "field": "status", "op": "eq", "value": { "lit": "in_progress" } }""");

        ScopePlan.Leaf leaf = assertInstanceOf(ScopePlan.Leaf.class, plan);
        ScopePlan.Operand.Literal literal =
                assertInstanceOf(ScopePlan.Operand.Literal.class, leaf.operand());
        assertEquals(FixtureStatus.IN_PROGRESS, literal.value());
    }

    @Test
    @DisplayName("T8: a write policy's CHECK clause may not name a field that only exists after the flush")
    void notCheckSafeIsRefusedAtSaveTime() {
        PolicyValidationException failure = assertThrows(PolicyValidationException.class, () ->
                compileCheck(Action.WRITE, """
                        { "field": "createdAt", "op": "gte", "value": { "ctx": "user.id" } }"""));

        // The point of the message: it is refused here, with a reason, rather
        // than discovered later as a create that is turned down for no visible
        // cause.
        assertTrue(failure.getMessage().contains("createdAt"));
        assertTrue(failure.getMessage().contains("saved"));
    }

    @Test
    @DisplayName("The same field is fine in the USING clause, which only ever runs on a row that exists")
    void notCheckSafeAppliesOnlyToTheCheckClause() {
        // USING is evaluated against a row loaded from the database, which
        // necessarily has a createdAt. Only the check on a freshly created row
        // would be reading a value that is not there yet, so restricting both
        // clauses would forbid a perfectly workable rule.
        ScopePlan plan = compile(Action.WRITE, """
                { "field": "createdAt", "op": "gte", "value": { "lit": "2020-01-01T00:00:00Z" } }""");
        assertInstanceOf(ScopePlan.Leaf.class, plan);
    }

    @Test
    @DisplayName("The same field is fine in a read policy — only write has the flush problem")
    void notCheckSafeAppliesOnlyToWrite() {
        ScopePlan plan = compile(Action.READ, """
                { "field": "createdAt", "op": "gte", "value": { "lit": "2020-01-01T00:00:00Z" } }""");
        assertInstanceOf(ScopePlan.Leaf.class, plan);
    }

    @Test
    @DisplayName("An unknown field is refused, and the message lists the ones that exist")
    void unknownFieldIsRefused() {
        PolicyValidationException failure = assertThrows(PolicyValidationException.class, () ->
                compile(Action.READ, """
                        { "field": "title", "op": "eq", "value": { "lit": "x" } }"""));

        assertTrue(failure.getMessage().contains("title"));
        assertTrue(failure.getMessage().contains("status"));
    }

    @Test
    @DisplayName("An operator the field's type cannot support is refused")
    void wrongOperatorForTypeIsRefused() {
        // Ordering an enum would compare the stored names alphabetically, which
        // looks like a working comparison and answers a different question.
        assertThrows(PolicyValidationException.class, () ->
                compile(Action.READ, """
                        { "field": "status", "op": "gt", "value": { "lit": "DONE" } }"""));
    }

    @Test
    @DisplayName("An unregistered context key is refused, and the message lists the registered ones")
    void unknownContextKeyIsRefused() {
        PolicyValidationException failure = assertThrows(PolicyValidationException.class, () ->
                compile(Action.READ, """
                        { "field": "assignee.id", "op": "eq", "value": { "ctx": "user.manager" } }"""));

        assertTrue(failure.getMessage().contains("user.manager"));
        assertTrue(failure.getMessage().contains("user.id"));
    }

    @Test
    @DisplayName("isNull must not be given a value, so a condition cannot silently mean something else")
    void nullaryOperatorsRejectAnOperand() {
        assertThrows(PolicyValidationException.class, () ->
                codec.parse("""
                        { "field": "dueDate", "op": "isNull", "value": { "lit": 1 } }"""));
    }

    @Test
    @DisplayName("The empty tree is the full scope")
    void emptyTreeIsUnrestricted() {
        assertTrue(compile(Action.READ, "{\"all\": []}").isUnrestricted());
    }

    private ScopePlan compile(Action action, String json) {
        return compiler.compile(widgets, action, codec.parse(json));
    }

    private ScopePlan compileCheck(Action action, String json) {
        return compiler.compileCheck(widgets, action, codec.parse(json));
    }

    private static boolean containsNoNegationNode(ScopePlan plan) {
        // There is no Not case in ScopePlan at all -- the type system already
        // says this. The assertion is here so that the day somebody adds one
        // back, this test is what tells them why they should not.
        return switch (plan) {
            case ScopePlan.Leaf ignored -> true;
            case ScopePlan.All all -> all.nodes().stream().allMatch(PolicyCompilerTest::containsNoNegationNode);
            case ScopePlan.Any any -> any.nodes().stream().allMatch(PolicyCompilerTest::containsNoNegationNode);
        };
    }

    private static RowLevelContextProvider provider(String key, Class<?> type) {
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
                return "user.id".equals(key) ? principal.userId() : principal.departmentId();
            }
        };
    }
}
