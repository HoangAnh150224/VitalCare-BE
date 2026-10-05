package com.vn.vitalcare.care.appointment.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BookingCodesTest {

    @Test
    @DisplayName("codes are eight characters with nothing that reads as another")
    void codesUseTheUnambiguousAlphabet() {
        for (int i = 0; i < 1000; i++) {
            String code = BookingCodes.generate();
            assertEquals(BookingCodes.LENGTH, code.length());
            assertTrue(code.chars().allMatch(c -> BookingCodes.ALPHABET.indexOf(c) >= 0), code);
            assertTrue(code.chars().noneMatch(c -> "01ILOU".indexOf(c) >= 0), code);
        }
    }

    @Test
    @DisplayName("a typed or scanned code is reduced to the stored form")
    void normalize() {
        assertEquals("K7M29QXA", BookingCodes.normalize("k7m2-9qxa"));
        assertEquals("K7M29QXA", BookingCodes.normalize("  K7M2 9QXA\n"));
        assertEquals("", BookingCodes.normalize(null));
    }
}
