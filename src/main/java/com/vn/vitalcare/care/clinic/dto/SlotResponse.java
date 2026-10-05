package com.vn.vitalcare.care.clinic.dto;

import com.vn.vitalcare.care.clinic.service.Slot;
import java.time.LocalTime;

/** A slot as the booking screen draws it: when, how many places, how many left, and whether it can be picked. */
public record SlotResponse(
        LocalTime startTime,
        LocalTime endTime,
        int capacity,
        long booked,
        long available,
        Slot.State state) {

    public static SlotResponse from(Slot slot) {
        return new SlotResponse(
                slot.startTime(), slot.endTime(), slot.capacity(), slot.booked(), slot.available(), slot.state());
    }
}
