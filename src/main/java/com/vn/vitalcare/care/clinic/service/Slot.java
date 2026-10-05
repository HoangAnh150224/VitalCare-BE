package com.vn.vitalcare.care.clinic.service;

import com.fasterxml.jackson.annotation.JsonValue;
import java.time.LocalTime;

/**
 * One bookable slot of one day.
 *
 * @param capacity  places in the slot
 * @param booked    places taken by scheduled or checked-in appointments
 * @param state     whether it can be booked, and if not, why
 */
public record Slot(LocalTime startTime, LocalTime endTime, int capacity, long booked, State state) {

    public long available() {
        return Math.max(0, capacity - booked);
    }

    public enum State {
        AVAILABLE("available"),
        FULL("full"),
        /** Already started, today. Checked before {@link #FULL}: a past slot is past whether or not it filled. */
        PAST("past");

        private final String value;

        State(String value) {
            this.value = value;
        }

        @JsonValue
        public String getValue() {
            return value;
        }
    }
}
