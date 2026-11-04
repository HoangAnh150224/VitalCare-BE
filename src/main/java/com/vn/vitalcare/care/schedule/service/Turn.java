package com.vn.vitalcare.care.schedule.service;

import java.time.LocalTime;

/** One patient's place in a doctor's day: the Nth turn is where queue number N is expected. */
public record Turn(LocalTime start, LocalTime end) {
}
