package com.vn.vitalcare.care.iomt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The band's own #v2 / #v3 log, read into the intake's upload. */
class BandLogTest {

    private static final String LOG = """
            #v2
            ts,t100,hr,spo2,conf,flags
            1793153983,3279,59,0,95,32
            1793153993,3278,62,98,98,0
            1793154022,3272,62,100,77,0
            1793154053,3267,0,0,0,36
            """;

    @Test
    @DisplayName("Each line becomes a reading: time from ts, temperature from t100, zero as no value")
    void readsLines() {
        DeviceUpload upload = BandLog.parse("vc-band-01", LOG, 0);

        assertThat(upload.readings()).hasSize(4);
        DeviceUpload.Reading first = upload.readings().getFirst();
        assertThat(first.clientRecordId()).isEqualTo("VC-BAND-01-1793153983");
        assertThat(first.measuredAt()).isEqualTo("2026-10-28T02:19:43Z");
        assertThat(first.heartRate()).isEqualTo(59);
        assertThat(first.spo2()).isNull();
        assertThat(first.skinTemperature()).isEqualByComparingTo(new BigDecimal("32.79"));
        // No contact: neither HR nor SpO₂, the temperature still kept.
        DeviceUpload.Reading lost = upload.readings().getLast();
        assertThat(lost.heartRate()).isNull();
        assertThat(lost.spo2()).isNull();
        assertThat(lost.skinTemperature()).isEqualByComparingTo(new BigDecimal("32.67"));
    }

    @Test
    @DisplayName("#v3 adds the body temperature from tb100; 0 there, as for SpO₂, means no value")
    void readsV3() {
        String v3 = "#v3\nts,t100,tb100,hr,spo2,conf,flags\n1761550000,3417,3637,62,98,95,128\n"
                + "1761550010,3416,0,64,0,92,160\n";
        assertThat(BandLog.looksLike(v3)).isTrue();
        DeviceUpload upload = BandLog.parse("vc-band-01", v3, 0);

        assertThat(upload.readings()).hasSize(2);
        DeviceUpload.Reading first = upload.readings().getFirst();
        assertThat(first.clientRecordId()).isEqualTo("VC-BAND-01-1761550000");
        assertThat(first.skinTemperature()).isEqualByComparingTo(new BigDecimal("34.17"));
        assertThat(first.bodyTemperature()).isEqualByComparingTo(new BigDecimal("36.37"));
        assertThat(first.heartRate()).isEqualTo(62);
        assertThat(first.spo2()).isEqualByComparingTo(new BigDecimal("98"));
        DeviceUpload.Reading second = upload.readings().getLast();
        assertThat(second.bodyTemperature()).isNull();
        assertThat(second.spo2()).isNull();
        assertThat(second.heartRate()).isEqualTo(64);
        assertThat(second.skinTemperature()).isEqualByComparingTo(new BigDecimal("34.16"));
        // A #v2 line has no body temperature.
        assertThat(BandLog.parse("vc-band-01", LOG, 0).readings()).allSatisfy(r -> assertThat(r.bodyTemperature()).isNull());
    }

    @Test
    @DisplayName("Below the confidence floor, heart rate and SpO₂ are dropped and the temperature kept")
    void confidenceFloor() {
        DeviceUpload.Reading low = BandLog.parse("VC-BAND-01", LOG, 80).readings().get(2);

        assertThat(low.heartRate()).isNull();
        assertThat(low.spo2()).isNull();
        assertThat(low.skinTemperature()).isEqualByComparingTo(new BigDecimal("32.72"));
    }

    @Test
    @DisplayName("Anything else is refused as a whole, with the line that could not be read")
    void refusesOtherText() {
        assertThat(BandLog.looksLike("  #v2\nts")).isTrue();
        assertThat(BandLog.looksLike("{\"readings\":[]}")).isFalse();
        assertThatThrownBy(() -> BandLog.parse("X", "#v1\nts,hr", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BandLog.parse("X", "#v2\nts,t100,spo2,conf\n1,2,3,4", 0))
                .hasMessageContaining("\"hr\"");
        assertThatThrownBy(() -> BandLog.parse("X", "#v2\nts,t100,hr,spo2\n17931,abc,1,1", 0))
                .hasMessageContaining("Line 3");
    }
}
