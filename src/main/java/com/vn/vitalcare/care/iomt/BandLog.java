package com.vn.vitalcare.care.iomt;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The wristband's own log, as it sends it (format {@code #v2}), read into the
 * same {@link DeviceUpload} as JSON — so a band can post its log as it is, over
 * REST ({@code text/csv}) or MQTT, and the intake does the rest.
 *
 * <pre>
 * #v2
 * ts,t100,hr,spo2,conf,flags
 * 1793153993,3278,62,98,98,0
 * </pre>
 * {@code ts} Unix seconds; {@code t100} skin temperature × 100; {@code hr}, {@code spo2} 0 when the
 * sensor has no value; {@code conf} confidence %; {@code flags} the band's status bits (not stored).
 * Each line's id is {@code <code>-<ts>}: the band resending its log stores nothing twice.
 */
public final class BandLog {

    public static final String MARKER = "#v2";
    private static final int MAX_LINES = 500;

    private BandLog() {
    }

    public static boolean looksLike(String body) {
        return body != null && body.stripLeading().startsWith(MARKER);
    }

    /**
     * @param minConfidence below it a line's heart rate and SpO₂ are dropped as unreliable (the
     *                      temperature is kept); 0 keeps everything
     * @throws IllegalArgumentException when it is not a {@code #v2} log or a line cannot be read
     */
    public static DeviceUpload parse(String deviceCode, String body, int minConfidence) {
        String[] lines = body.strip().split("\\r?\\n");
        if (lines.length < 2 || !MARKER.equals(lines[0].strip())) {
            throw new IllegalArgumentException("Not a #v2 band log");
        }
        List<String> header = List.of(lines[1].strip().toLowerCase(Locale.ROOT).split(","));
        int ts = column(header, "ts");
        int t100 = column(header, "t100");
        int hr = column(header, "hr");
        int spo2 = column(header, "spo2");
        int conf = header.indexOf("conf");
        if (lines.length - 2 > MAX_LINES) {
            throw new IllegalArgumentException("At most " + MAX_LINES + " lines per upload");
        }

        String code = deviceCode.trim().toUpperCase(Locale.ROOT);
        List<DeviceUpload.Reading> readings = new ArrayList<>(lines.length - 2);
        for (int i = 2; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty()) {
                continue;
            }
            String[] f = line.split(",");
            try {
                long seconds = Long.parseLong(f[ts].strip());
                boolean trusted = conf < 0 || conf >= f.length || Integer.parseInt(f[conf].strip()) >= minConfidence;
                Integer heartRate = trusted ? positive(f[hr]) : null;
                Integer oxygen = trusted ? positive(f[spo2]) : null;
                Integer centi = positive(f[t100]);
                if (heartRate == null && oxygen == null && centi == null) {
                    continue;
                }
                readings.add(new DeviceUpload.Reading(
                        code + "-" + seconds,
                        Instant.ofEpochSecond(seconds).atOffset(ZoneOffset.UTC).toString(),
                        oxygen == null ? null : BigDecimal.valueOf(oxygen),
                        heartRate,
                        centi == null ? null : BigDecimal.valueOf(centi, 2),
                        null));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Line " + (i + 1) + " cannot be read: " + line);
            }
        }
        return new DeviceUpload(null, null, null, null, null, readings);
    }

    private static int column(List<String> header, String name) {
        int i = header.indexOf(name);
        if (i < 0) {
            throw new IllegalArgumentException("The log has no \"" + name + "\" column");
        }
        return i;
    }

    private static Integer positive(String value) {
        int v = Integer.parseInt(value.strip());
        return v > 0 ? v : null;
    }
}
