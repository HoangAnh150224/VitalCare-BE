package com.vn.vitalcare.care.customer;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * What an identity number looks like for each kind of paper: a citizen card
 * (CCCD) and a personal identification number are the same twelve digits, a
 * passport six to nine letters and digits (a Vietnamese one: a letter and
 * seven digits). Checked on save, as the profile form checks it.
 */
public final class NationalIds {

    private static final Pattern TWELVE_DIGITS = Pattern.compile("[0-9]{12}");
    private static final Map<String, Pattern> FORMATS = Map.of(
            "cccd", TWELVE_DIGITS,
            "personal_id", TWELVE_DIGITS,
            "passport", Pattern.compile("[A-Z0-9]{6,9}"));

    private NationalIds() {
    }

    /** The number as stored: trimmed, and a passport's letters in capitals. */
    public static String normalize(String type, String number) {
        String trimmed = number == null ? "" : number.trim();
        return "passport".equals(type) ? trimmed.toUpperCase(java.util.Locale.ROOT) : trimmed;
    }

    /** Whether the (normalized) number fits the kind of paper; an unknown kind fits nothing. */
    public static boolean matches(String type, String number) {
        Pattern format = FORMATS.get(type);
        return format != null && format.matcher(normalize(type, number)).matches();
    }
}
