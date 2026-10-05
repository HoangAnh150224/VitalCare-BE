package com.vn.vitalcare.care.appointment.service;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * The code printed on an appointment slip and carried by its QR.
 *
 * <p>Eight characters from 30 symbols — digits and letters without 0/O, 1/I/L
 * or U — so a code read off a phone screen or a printed slip cannot be
 * mistyped into a different valid one. That is about 6.6 × 10^11 codes:
 * guessing a live one is hopeless, and looking one up needs
 * {@code appointments:read} anyway.
 */
public final class BookingCodes {

    static final String ALPHABET = "23456789ABCDEFGHJKMNPQRSTVWXYZ";

    static final int LENGTH = 8;

    private static final SecureRandom RANDOM = new SecureRandom();

    private BookingCodes() {
    }

    public static String generate() {
        StringBuilder code = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }

    /**
     * A code as somebody typed or a scanner read it, in the stored form:
     * separators and spaces dropped, upper case. {@code k7m2-9qxa} finds
     * {@code K7M29QXA}.
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replaceAll("[\\s\\h-]", "").toUpperCase(Locale.ROOT);
    }
}
