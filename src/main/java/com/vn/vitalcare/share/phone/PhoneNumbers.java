package com.vn.vitalcare.share.phone;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one definition of what a phone number is in this system.
 *
 * <p>Sign-in, account administration and self-registration all key on the same
 * column, so they must agree on every spelling to the letter: if registration
 * stored a form that sign-in normalised differently, the account would exist
 * and be unreachable. Hence one place, rather than a copy per caller.
 */
public final class PhoneNumbers {

    /**
     * The spellings of a Vietnamese mobile number this system accepts, with the
     * nine significant digits captured.
     *
     * <p>Carrier prefixes are deliberately not enumerated. A list of them has
     * to be edited every time Vietnam allocates a new one, and the cost of
     * that maintenance outweighs catching a number with a prefix nobody issues
     * — which is a wrong number either way, and a thing business validation
     * can take up later if it ever matters.
     */
    private static final Pattern PHONE = Pattern.compile("^(?:\\+84|84|0)(\\d{9})$");

    /**
     * What people put between the digits, and nothing else.
     *
     * <p>{@code \h} as well as {@code \s}, because Java's {@code \s} is ASCII
     * only, and a number copied from a web page or a chat app routinely
     * carries a no-break space ({@code U+00A0}, {@code U+202F}). Without it a
     * correctly typed number answers "incorrect phone number or password".
     */
    private static final Pattern SEPARATORS = Pattern.compile("[\\s\\h.()-]");

    private PhoneNumbers() {
    }

    /**
     * Converts any accepted spelling of a Vietnamese mobile number to the one
     * form the column stores, E.164 — {@code 0901234567}, {@code 84901234567}
     * and {@code +84 901 234 567} all become {@code +84901234567}.
     *
     * <p>This is what makes {@code uq_users_phone} mean "one account per
     * number". Without it the constraint is satisfied by four spellings of the
     * same line, and which account a sign-in finds depends on how the person
     * happened to type it.
     *
     * <p>Empty when the value is not a number this system can key on.
     */
    public static Optional<String> normalize(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        Matcher matcher = PHONE.matcher(SEPARATORS.matcher(raw).replaceAll(""));
        return matcher.matches() ? Optional.of("+84" + matcher.group(1)) : Optional.empty();
    }

    /**
     * A search term reduced to the digits that appear in a stored number.
     *
     * <p>Drops the separators and then the trunk zero or country code, so
     * {@code 0901234567}, {@code +84 901 234 567} and {@code 901234} all
     * address the same stored {@code +84901234567}.
     *
     * <p>A prefix is only dropped while something is left to match on, so that
     * searching {@code 0} or {@code 84} narrows by those digits instead of
     * collapsing to the empty term that matches every row.
     */
    public static String searchTail(String raw) {
        String digits = raw.replaceAll("[\\s\\h.()+-]", "");
        if (digits.length() > 2 && digits.startsWith("84")) {
            return digits.substring(2);
        }
        if (digits.length() > 1 && digits.startsWith("0")) {
            return digits.substring(1);
        }
        return digits;
    }

    /**
     * A normalised number with all but its last three digits hidden, for log
     * lines that need to tell two numbers apart without recording either.
     */
    public static String mask(String normalized) {
        if (normalized == null || normalized.length() <= 3) {
            return "***";
        }
        return "*".repeat(normalized.length() - 3) + normalized.substring(normalized.length() - 3);
    }
}
