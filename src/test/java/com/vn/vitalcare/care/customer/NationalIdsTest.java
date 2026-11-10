package com.vn.vitalcare.care.customer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** An identity number has to fit the kind of paper chosen. */
class NationalIdsTest {

    @Test
    @DisplayName("a citizen card or personal number is twelve digits; a passport six to nine letters and digits")
    void formats() {
        assertTrue(NationalIds.matches("cccd", "001190004521"));
        assertTrue(NationalIds.matches("personal_id", " 001218009873 "));
        assertFalse(NationalIds.matches("cccd", "12345678"));
        assertFalse(NationalIds.matches("cccd", "00119000452A"));
        assertFalse(NationalIds.matches("personal_id", "0011900045211"));
        assertTrue(NationalIds.matches("passport", "c1234567"));
        assertFalse(NationalIds.matches("passport", "C12"));
        assertFalse(NationalIds.matches("passport", "C123-4567"));
        assertFalse(NationalIds.matches("unknown", "001190004521"));
        assertEquals("C1234567", NationalIds.normalize("passport", " c1234567 "));
    }
}
