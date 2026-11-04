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
    // Not even a nought after the country code is refused: accounts exist under such numbers (the
    // test team's seed customer is 0000000131), and refusing that shape would lock them out.
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
     * Why a number is refused, said precisely (the test team's ST-REG-011..017):
     * a character that is not a digit, too few digits, or too many; or, for
     * anything else this system cannot key on, that it is no Vietnamese number.
     * Empty for a number {@link #normalize} accepts.
     */
    public static Optional<String> problem(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.of("Phone number is required");
        }
        if (normalize(raw).isPresent()) {
            return Optional.empty();
        }
        String compact = SEPARATORS.matcher(raw).replaceAll("");
        boolean plus = compact.startsWith("+");
        String digits = plus ? compact.substring(1) : compact;
        if (!digits.matches("\\d+")) {
            return Optional.of(NOT_DIGITS);
        }
        String significant;
        if (plus || digits.startsWith("84")) {
            significant = digits.startsWith("84") ? digits.substring(2) : null;
        } else if (digits.startsWith("0")) {
            significant = digits.substring(1);
        } else {
            // No country code and no trunk zero: short is short; ten digits or more is just not one of ours.
            return Optional.of(digits.length() < 10 ? TOO_FEW : NOT_VIETNAMESE);
        }
        // The country code and the trunk zero both (+84 0912...): not a count of digits gone wrong but a
        // number written two ways at once (ST-REG-024).
        if (significant == null || (significant.length() == 10 && significant.startsWith("0"))) {
            return Optional.of(NOT_VIETNAMESE);
        }
        return Optional.of(significant.length() < 9 ? TOO_FEW : significant.length() > 9 ? TOO_MANY : NOT_VIETNAMESE);
    }

    public static final String NOT_DIGITS = "Phone number may only contain digits, spaces and a leading +";
    public static final String TOO_FEW = "Phone number has too few digits";
    public static final String TOO_MANY = "Phone number has too many digits";
    public static final String NOT_VIETNAMESE = "Phone number must be a Vietnamese number, such as 0901234567 or +84901234567";

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
