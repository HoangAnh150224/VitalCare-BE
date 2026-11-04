package com.vn.vitalcare.share.phone;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The test team's ST-REG-011..017: a refused number says which way it is wrong. */
class PhoneProblemTest {

    @Test
    @DisplayName("too few digits, too many, or a character that is not a digit; nothing for a number in use")
    void problems() {
        assertThat(PhoneNumbers.problem("12345")).contains(PhoneNumbers.TOO_FEW);
        assertThat(PhoneNumbers.problem("039847482")).contains(PhoneNumbers.TOO_FEW);
        assertThat(PhoneNumbers.problem("+8490123456")).contains(PhoneNumbers.TOO_FEW);
        assertThat(PhoneNumbers.problem("09123456789")).contains(PhoneNumbers.TOO_MANY);
        assertThat(PhoneNumbers.problem("+84 9012 345 6789")).contains(PhoneNumbers.TOO_MANY);
        assertThat(PhoneNumbers.problem("abcdefghij")).contains(PhoneNumbers.NOT_DIGITS);
        assertThat(PhoneNumbers.problem("abc0912345678")).contains(PhoneNumbers.NOT_DIGITS);
        assertThat(PhoneNumbers.problem("09@1234567")).contains(PhoneNumbers.NOT_DIGITS);
        assertThat(PhoneNumbers.problem("+1202555019")).contains(PhoneNumbers.NOT_VIETNAMESE);
        assertThat(PhoneNumbers.problem("+840912345678")).contains(PhoneNumbers.NOT_VIETNAMESE);
        assertThat(PhoneNumbers.problem("+12025550123")).contains(PhoneNumbers.NOT_VIETNAMESE);
        assertThat(PhoneNumbers.problem("1234567890")).contains(PhoneNumbers.NOT_VIETNAMESE);
        assertThat(PhoneNumbers.problem("0912 345 678")).isEmpty();
        assertThat(PhoneNumbers.problem("+84 000 000 131")).isEmpty();
    }
}
