package com.vn.vitalcare.care.schedule.dto;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * What registering with one doctor on one day would get: the queue number and
 * about when it will be called.
 *
 * @param working         the doctor sees patients that day at all
 * @param capacity        how many patients the day holds
 * @param taken           people registered and still to be seen
 * @param remaining       turns still ahead that nobody is waiting for
 * @param nextNumber      the number the next registration gets; null when there is none to give
 * @param estimatedStart  about when that number is seen; null with {@code nextNumber}
 * @param bookable        whether a registration would be accepted now
 */
public record DayAvailability(
        LocalDate date,
        boolean working,
        int capacity,
        long taken,
        long remaining,
        Integer nextNumber,
        LocalTime estimatedStart,
        LocalTime estimatedEnd,
        boolean bookable) {
}
