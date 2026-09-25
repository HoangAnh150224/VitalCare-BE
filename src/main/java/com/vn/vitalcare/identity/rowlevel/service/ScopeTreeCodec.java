package com.vn.vitalcare.identity.rowlevel.service;

import com.vn.vitalcare.share.security.rowlevel.Op;
import com.vn.vitalcare.share.security.rowlevel.PolicyValidationException;
import com.vn.vitalcare.share.security.rowlevel.ScopeTree;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Reads and writes the JSON a policy is stored as.
 *
 * <p>Hand-rolled against {@link JsonNode} rather than left to Jackson's
 * polymorphic deserialisation, for one reason: the errors. A policy is written
 * by a person in an admin screen, and "unknown operator \"contains\"" pointing
 * at {@code any[1]} is worth a great deal more than a stack trace about a type
 * id. The grammar is four node shapes; a parser for it is short.
 *
 * <p>Every failure carries a pointer to the offending node, built as the parser
 * descends, so nothing has to be reconstructed afterwards.
 */
@Component
public class ScopeTreeCodec {

    private final ObjectMapper mapper;

    public ScopeTreeCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Parses the stored JSON. Never returns null: an absent or blank tree is the full scope. */
    public ScopeTree parse(String json) {
        if (json == null || json.isBlank()) {
            return ScopeTree.unrestricted();
        }
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (JacksonException e) {
            throw new PolicyValidationException("scope", "is not valid JSON");
        }
        return node(root, "scope");
    }

    /** Parses a tree that arrived as part of a request body rather than out of a column. */
    public ScopeTree parse(JsonNode json) {
        return node(json, "scope");
    }

    /** The stored JSON as a node, so a response carries the tree rather than a string of it. */
    public JsonNode read(String json) {
        if (json == null || json.isBlank()) {
            return mapper.createObjectNode().set("all", mapper.createArrayNode());
        }
        try {
            return mapper.readTree(json);
        } catch (JacksonException e) {
            // A row whose JSON will not parse has already been quarantined; the
            // response still has to say something, and an empty object is
            // honest about there being nothing readable there.
            return mapper.createObjectNode();
        }
    }

    /** Serialises a tree back to the form it is stored in. */
    public String write(ScopeTree tree) {
        try {
            return mapper.writeValueAsString(toJson(tree));
        } catch (JacksonException e) {
            // Only reachable if a literal held something Jackson cannot write,
            // and every literal came out of Jackson in the first place.
            throw new IllegalStateException("Could not serialise a scope tree", e);
        }
    }

    private ScopeTree node(JsonNode json, String at) {
        if (json == null || json.isNull()) {
            return ScopeTree.unrestricted();
        }
        if (!json.isObject()) {
            throw new PolicyValidationException(at, "must be an object");
        }

        boolean all = json.has("all");
        boolean any = json.has("any");
        boolean not = json.has("not");
        boolean leaf = json.has("field");

        int declared = (all ? 1 : 0) + (any ? 1 : 0) + (not ? 1 : 0) + (leaf ? 1 : 0);
        if (declared == 0) {
            throw new PolicyValidationException(at, "must be one of all, any, not or a field condition");
        }
        if (declared > 1) {
            throw new PolicyValidationException(at, "must be exactly one of all, any, not or a field condition");
        }

        if (all) {
            return new ScopeTree.All(children(json.get("all"), at + ".all"));
        }
        if (any) {
            return new ScopeTree.Any(children(json.get("any"), at + ".any"));
        }
        if (not) {
            return new ScopeTree.Not(node(json.get("not"), at + ".not"));
        }
        return leaf(json, at);
    }

    private List<ScopeTree> children(JsonNode json, String at) {
        if (!json.isArray()) {
            throw new PolicyValidationException(at, "must be an array of conditions");
        }
        List<ScopeTree> nodes = new ArrayList<>();
        for (int i = 0; i < json.size(); i++) {
            nodes.add(node(json.get(i), "%s[%d]".formatted(at, i)));
        }
        return nodes;
    }

    private ScopeTree leaf(JsonNode json, String at) {
        JsonNode field = json.get("field");
        if (!field.isTextual() || field.asText().isBlank()) {
            throw new PolicyValidationException(at + ".field", "must name a field");
        }

        JsonNode operator = json.get("op");
        if (operator == null || !operator.isTextual()) {
            throw new PolicyValidationException(at + ".op", "must name an operator");
        }
        Op op;
        try {
            op = Op.from(operator.asText());
        } catch (IllegalArgumentException e) {
            throw new PolicyValidationException(at + ".op", e.getMessage());
        }

        JsonNode value = json.get("value");

        // The two nullary operators must not be given an operand. The grammar
        // could have made `value` optional and left it at that, but silently
        // ignoring one written by mistake would leave somebody convinced their
        // condition said something it does not.
        if (op.isNullary()) {
            if (value != null && !value.isNull()) {
                throw new PolicyValidationException(
                        at + ".value", "%s takes no value".formatted(op.code()));
            }
            return new ScopeTree.Leaf(field.asText(), op, null);
        }

        if (value == null || value.isNull()) {
            throw new PolicyValidationException(at + ".value", "%s needs a value".formatted(op.code()));
        }
        return new ScopeTree.Leaf(field.asText(), op, value(value, at + ".value"));
    }

    private ScopeTree.Value value(JsonNode json, String at) {
        if (!json.isObject()) {
            throw new PolicyValidationException(at, "must be {\"lit\": ...} or {\"ctx\": \"...\"}");
        }
        boolean lit = json.has("lit");
        boolean ctx = json.has("ctx");
        if (lit == ctx) {
            throw new PolicyValidationException(at, "must be exactly one of lit or ctx");
        }
        if (ctx) {
            JsonNode key = json.get("ctx");
            if (!key.isTextual() || key.asText().isBlank()) {
                throw new PolicyValidationException(at + ".ctx", "must name a context key");
            }
            return new ScopeTree.Value.Ctx(key.asText().trim());
        }
        return new ScopeTree.Value.Lit(plain(json.get("lit")));
    }

    /**
     * Jackson's node types flattened to plain Java, so that coercion later has
     * one small set of inputs to reason about rather than the whole
     * {@code JsonNode} hierarchy.
     */
    private Object plain(JsonNode json) {
        if (json == null || json.isNull()) {
            return null;
        }
        if (json.isArray()) {
            List<Object> values = new ArrayList<>(json.size());
            for (int i = 0; i < json.size(); i++) {
                values.add(plain(json.get(i)));
            }
            return values;
        }
        if (json.isTextual()) {
            return json.asText();
        }
        if (json.isBoolean()) {
            return json.asBoolean();
        }
        if (json.isIntegralNumber()) {
            return json.asLong();
        }
        if (json.isNumber()) {
            return json.decimalValue();
        }
        throw new PolicyValidationException(null, "unsupported literal " + json);
    }

    private JsonNode toJson(ScopeTree tree) {
        ObjectNode node = mapper.createObjectNode();
        switch (tree) {
            case ScopeTree.All all -> node.set("all", array(all.nodes()));
            case ScopeTree.Any any -> node.set("any", array(any.nodes()));
            case ScopeTree.Not not -> node.set("not", toJson(not.node()));
            case ScopeTree.Leaf leaf -> {
                node.put("field", leaf.field());
                node.put("op", leaf.op().code());
                if (leaf.value() != null) {
                    ObjectNode value = mapper.createObjectNode();
                    switch (leaf.value()) {
                        case ScopeTree.Value.Ctx ctx -> value.put("ctx", ctx.key());
                        case ScopeTree.Value.Lit lit ->
                                value.set("lit", mapper.valueToTree(literal(lit.value())));
                    }
                    node.set("value", value);
                }
            }
        }
        return node;
    }

    private ArrayNode array(List<ScopeTree> nodes) {
        ArrayNode array = mapper.createArrayNode();
        nodes.forEach(node -> array.add(toJson(node)));
        return array;
    }

    /** Keeps a {@link BigDecimal} out of the JSON as a bare number rather than a string. */
    private Object literal(Object value) {
        return value instanceof BigDecimal decimal ? decimal : value;
    }
}
