package com.vn.vitalcare.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** What a first-aid article is about; a fixed list, the chips patients filter by. */
public enum FirstAidCategory {

    RESPIRATORY,

    FAINTING,

    FEVER,

    WOUND,

    INJURY,

    ENVIRONMENT,

    GENERAL;

    @JsonValue
    public String getValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static FirstAidCategory from(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown first-aid category: " + raw);
        }
    }
}
