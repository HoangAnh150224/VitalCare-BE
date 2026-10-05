package com.vn.vitalcare.care.clinic.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * One day's slots, with what the booking screen needs to explain an empty
 * grid: whether the clinic opens that day at all, and whether the day can be
 * booked yet.
 *
 * @param open              the clinic has sessions on that weekday
 * @param bookable          the day is between today and the end of the
 *                          booking window, as the clinic counts days
 * @param lastBookableDate  the end of that window, so the date picker can stop
 *                          there instead of the client repeating the setting
 */
public record DaySlotsResponse(
        LocalDate date,
        boolean open,
        boolean bookable,
        LocalDate lastBookableDate,
        List<SlotResponse> slots) {
}
