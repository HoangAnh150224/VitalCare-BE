package com.vn.vitalcare.care.registration.dto;

import com.vn.vitalcare.entity.Customer;

/**
 * A profile with the number being registered and no account yet, as the
 * person registering sees it (BR-14): the name masked, only the birth year.
 * Shown only once the code has proven the number is theirs.
 */
public record ProfileMatch(Long id, String maskedName, Integer birthYear) {

    public static ProfileMatch from(Customer profile) {
        return new ProfileMatch(profile.getId(), mask(profile.getFullName()),
                profile.getDateOfBirth() == null ? null : profile.getDateOfBirth().getYear());
    }

    /**
     * "Nguyễn Văn A" → "Nguyễn V*** A": the first and last words whole, the
     * ones between as their first letter. A name of two words keeps the first
     * and masks the last ("Trần Bình" → "Trần B***"); one word keeps its first letter.
     */
    public static String mask(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return "***";
        }
        String[] words = fullName.trim().split("\\s+");
        if (words.length == 1) {
            return initial(words[0]);
        }
        StringBuilder masked = new StringBuilder(words[0]);
        for (int i = 1; i < words.length; i++) {
            boolean last = i == words.length - 1;
            masked.append(' ').append(last && words.length > 2 ? words[i] : initial(words[i]));
        }
        return masked.toString();
    }

    /** The word's first letter, whole even when it is an accented one written as two code points. */
    private static String initial(String word) {
        int end = word.offsetByCodePoints(0, 1);
        while (end < word.length() && Character.getType(word.charAt(end)) == Character.NON_SPACING_MARK) {
            end++;
        }
        return word.substring(0, end) + "***";
    }
}
