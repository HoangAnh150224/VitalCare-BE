package com.vn.vitalcare.care.clinic;

import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Clinic settings, bound from {@code app.clinic.*}.
 *
 * @param timeZone           where the clinic counts its days. Appointment dates
 *                           are local dates, so "is this appointment today"
 *                           only has an answer in a zone.
 * @param bookingWindowDays  how far ahead a slot may be booked, counted from
 *                           today in {@code timeZone}
 */
@ConfigurationProperties(prefix = "app.clinic")
public record ClinicProperties(ZoneId timeZone, int bookingWindowDays) {
}
