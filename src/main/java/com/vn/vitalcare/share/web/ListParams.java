package com.vn.vitalcare.share.web;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.util.MultiValueMap;

import java.util.*;

/**
 * The query string of Refine's {@code simple-rest} data provider, parsed.
 *
 * <p>That provider is the contract this backend has to satisfy; it sends
 *
 * <pre>
 *   GET /blog_posts?_start=0&amp;_end=10&amp;_sort=title,status&amp;_order=asc,desc
 *                  &amp;title_like=foo&amp;status=published&amp;category.id=3&amp;q=lorem
 * </pre>
 *
 * and, for {@code getMany}, {@code ?ids[0]=1&ids[1]=2} (the bracket form comes
 * from {@code qs.stringify}, so Spring cannot bind it to a {@code List} on its
 * own). Everything that is not one of the {@code _}-prefixed control params,
 * {@code q} or {@code ids} is a filter: a field name with an operator suffix.
 *
 * <p>The response side of the contract lives in {@link ListResponse}: a bare
 * JSON array plus the total row count in {@code X-Total-Count}.
 */
public final class ListParams {

    /** Filter operators the provider can emit, keyed by the suffix it appends. */
    public enum Operator {
        EQ(""),
        NE("_ne"),
        GTE("_gte"),
        LTE("_lte"),
        LIKE("_like");

        private final String suffix;

        Operator(String suffix) {
            this.suffix = suffix;
        }

        public String suffix() {
            return suffix;
        }
    }

    /** One parsed {@code field[_op]=value} pair. */
    public record Criterion(String field, Operator operator, String value) {}

    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 1_000;

    private final MultiValueMap<String, String> raw;

    public ListParams(MultiValueMap<String, String> raw) {
        this.raw = raw;
    }

    /**
     * {@code _start}/{@code _end} translated to a {@link Pageable}.
     *
     * <p>Only the fields in {@code sortable} are accepted as sort properties —
     * anything else is dropped rather than handed to JPA, which would answer an
     * unknown property with a 500.
     */
    public Pageable pageable(Set<String> sortable, Sort fallback) {
        int start = Math.max(intParam("_start", 0), 0);
        int end = intParam("_end", start + DEFAULT_PAGE_SIZE);

        int size = Math.min(Math.max(end - start, 1), MAX_PAGE_SIZE);
        int page = start / size;

        Sort sort = sort(sortable);
        return PageRequest.of(page, size, sort.isSorted() ? sort : fallback);
    }

    /** {@code _sort=a,b} + {@code _order=asc,desc} as a {@link Sort}. */
    private Sort sort(Set<String> sortable) {
        String sortParam = raw.getFirst("_sort");
        if (sortParam == null || sortParam.isBlank()) {
            return Sort.unsorted();
        }

        String[] fields = sortParam.split(",");
        String orderParam = Optional.ofNullable(raw.getFirst("_order")).orElse("");
        String[] orders = orderParam.split(",");

        List<Sort.Order> orderList = new ArrayList<>();
        for (int i = 0; i < fields.length; i++) {
            String field = fields[i].trim();
            if (!sortable.contains(field)) {
                continue;
            }
            String direction = i < orders.length ? orders[i].trim() : "asc";
            orderList.add("desc".equalsIgnoreCase(direction) ? Sort.Order.desc(field) : Sort.Order.asc(field));
        }
        return orderList.isEmpty() ? Sort.unsorted() : Sort.by(orderList);
    }

    /**
     * Every filter param, with its operator suffix stripped off the field name.
     *
     * <p>Longest suffix first, so {@code createdAt_gte} is not mistaken for a
     * field literally called {@code createdAt_gte} matched with {@code EQ}.
     */
    public List<Criterion> filters() {
        List<Criterion> criteria = new ArrayList<>();

        for (Map.Entry<String, List<String>> entry : sortedByKey().entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("_") || key.equals("q") || key.equals("ids") || key.startsWith("ids[")) {
                continue;
            }
            String value = entry.getValue().isEmpty() ? null : entry.getValue().getFirst();
            if (value == null || value.isBlank()) {
                continue;
            }

            Operator operator = Operator.EQ;
            String field = key;
            for (Operator candidate : Operator.values()) {
                if (!candidate.suffix().isEmpty() && key.endsWith(candidate.suffix())) {
                    operator = candidate;
                    field = key.substring(0, key.length() - candidate.suffix().length());
                    break;
                }
            }
            criteria.add(new Criterion(field, operator, value));
        }
        return criteria;
    }

    /** The {@code q} full-text term, if the quick filter is in use. */
    public Optional<String> search() {
        String q = raw.getFirst("q");
        return q == null || q.isBlank() ? Optional.empty() : Optional.of(q.trim());
    }

    /**
     * The {@code getMany} id list. Accepts {@code ids=1&ids=2}, {@code ids[0]=1}
     * and {@code ids[]=1}; non-numeric entries are dropped.
     */
    public List<Long> ids() {
        List<Long> ids = new ArrayList<>();
        raw.forEach((key, values) -> {
            if (!key.equals("ids") && !key.startsWith("ids[")) {
                return;
            }
            for (String value : values) {
                try {
                    ids.add(Long.valueOf(value.trim()));
                } catch (NumberFormatException ignored) {
                    // a non-numeric id simply cannot match a row
                }
            }
        });
        return ids;
    }

    private Map<String, List<String>> sortedByKey() {
        // LinkedHashMap over the raw entries: iteration order does not matter
        // for correctness, only for reproducible error messages.
        return new LinkedHashMap<>(raw);
    }

    private int intParam(String name, int fallback) {
        String value = raw.getFirst(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Case-insensitive {@code LIKE} pattern for a {@code _like} value. */
    public static String likePattern(String value) {
        return "%" + value.toLowerCase(Locale.ROOT) + "%";
    }

    /** Each Vietnamese marked vowel (and đ), in lower case, beside the letter it is written on. */
    private static final String[][] MARKS = {
            {"àáạảãâầấậẩẫăằắặẳẵ", "a"}, {"èéẹẻẽêềếệểễ", "e"}, {"ìíịỉĩ", "i"},
            {"òóọỏõôồốộổỗơờớợởỡ", "o"}, {"ùúụủũưừứựửữ", "u"}, {"ỳýỵỷỹ", "y"}, {"đ", "d"}};
    private static final String MARKED;
    private static final String PLAIN;

    static {
        StringBuilder marked = new StringBuilder();
        StringBuilder plain = new StringBuilder();
        for (String[] mark : MARKS) {
            marked.append(mark[0]);
            plain.append(mark[1].repeat(mark[0].length()));
        }
        MARKED = marked.toString();
        PLAIN = plain.toString();
    }

    /**
     * A column lower-cased and without Vietnamese marks, so {@code foldedPattern("tran thi bich")}
     * finds "Trần Thị Bích". Plain SQL {@code translate}, so no database extension is needed.
     */
    public static Expression<String> folded(CriteriaBuilder cb, Expression<String> value) {
        return cb.function("translate", String.class, cb.lower(value), cb.literal(MARKED), cb.literal(PLAIN));
    }

    /** The {@code LIKE} pattern to compare with {@link #folded}: the term folded the same way. */
    public static String foldedPattern(String value) {
        String lower = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(lower.length());
        for (char c : lower.toCharArray()) {
            int at = MARKED.indexOf(c);
            out.append(at < 0 ? c : PLAIN.charAt(at));
        }
        return "%" + out + "%";
    }
}
