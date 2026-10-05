package com.vn.vitalcare.care.clinic.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vn.vitalcare.care.clinic.dto.WorkingHoursEntry;
import com.vn.vitalcare.share.exception.FieldValidationException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Slots cut from opening hours, and the rules a week of hours must follow. */
class ClinicScheduleServiceTest {

    /** 10:00 on Monday 5 October in Ho Chi Minh City. */
    private static final Instant NOW = Instant.parse("2026-10-05T03:00:00Z");
    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 5);

    private ScheduleFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new ScheduleFixture(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("a session is cut into consecutive slots of its own length")
    void sessionsAreCutIntoSlots() {
        List<Slot> slots = fixture.schedule.slotsFor(fixture.clinic, MONDAY.plusDays(1));

        // 07:30–11:30 is eight half hours, 13:30–16:30 is six.
        assertEquals(14, slots.size());
        assertEquals(LocalTime.of(7, 30), slots.get(0).startTime());
        assertEquals(LocalTime.of(8, 0), slots.get(0).endTime());
        assertEquals(LocalTime.of(11, 0), slots.get(7).startTime());
        assertEquals(LocalTime.of(13, 30), slots.get(8).startTime());
        assertEquals(LocalTime.of(16, 0), slots.get(13).startTime());
        assertTrue(slots.stream().allMatch(slot -> slot.state() == Slot.State.AVAILABLE));
    }

    @Test
    @DisplayName("a weekday with no session has no slots")
    void closedDayHasNoSlots() {
        LocalDate sunday = MONDAY.plusDays(6);

        assertTrue(fixture.schedule.slotsFor(fixture.clinic, sunday).isEmpty());
        assertFalse(fixture.schedule.isOpenOn(fixture.clinic, sunday));
    }

    @Test
    @DisplayName("today, a slot that has already started is past")
    void startedSlotsArePast() {
        List<Slot> slots = fixture.schedule.slotsFor(fixture.clinic, MONDAY);

        // 07:30 … 10:00 have started by 10:00; 10:30 has not.
        assertEquals(Slot.State.PAST, slot(slots, "07:30").state());
        assertEquals(Slot.State.PAST, slot(slots, "10:00").state());
        assertEquals(Slot.State.AVAILABLE, slot(slots, "10:30").state());
    }

    @Test
    @DisplayName("a slot with every place taken is full, and says how many are left")
    void fullSlot() {
        fixture.booked.put(LocalTime.of(14, 0), 3L);
        fixture.booked.put(LocalTime.of(14, 30), 1L);

        List<Slot> slots = fixture.schedule.slotsFor(fixture.clinic, MONDAY);

        assertEquals(Slot.State.FULL, slot(slots, "14:00").state());
        assertEquals(0, slot(slots, "14:00").available());
        assertEquals(Slot.State.AVAILABLE, slot(slots, "14:30").state());
        assertEquals(2, slot(slots, "14:30").available());
    }

    @Test
    @DisplayName("'today' is the clinic's day: at 01:00 local nothing has started yet")
    void pastIsCountedInTheClinicsZone() {
        // 18:00 UTC on 4 October is 01:00 on 5 October in Ho Chi Minh City.
        fixture = new ScheduleFixture(Clock.fixed(Instant.parse("2026-10-04T18:00:00Z"), ZoneOffset.UTC));

        List<Slot> slots = fixture.schedule.slotsFor(fixture.clinic, MONDAY);

        assertTrue(slots.stream().allMatch(slot -> slot.state() == Slot.State.AVAILABLE));
    }

    @Test
    @DisplayName("two sessions of one day may touch but not overlap")
    void overlappingSessionsAreRefused() {
        List<WorkingHoursEntry> touching = List.of(
                entry(1, "07:30", "11:30"), entry(1, "11:30", "16:30"), entry(2, "08:00", "12:00"));
        fixture.schedule.replaceWeek(fixture.clinic, touching);

        List<WorkingHoursEntry> overlapping = List.of(entry(1, "07:30", "11:30"), entry(1, "11:00", "16:30"));
        FieldValidationException e = assertThrows(FieldValidationException.class,
                () -> fixture.schedule.replaceWeek(fixture.clinic, overlapping));
        assertEquals("sessions", e.field());
    }

    @Test
    @DisplayName("a session must close after it opens")
    void backwardsSessionIsRefused() {
        assertThrows(FieldValidationException.class,
                () -> fixture.schedule.replaceWeek(fixture.clinic, List.of(entry(1, "11:30", "07:30"))));
    }

    private static Slot slot(List<Slot> slots, String start) {
        LocalTime time = LocalTime.parse(start);
        return slots.stream().filter(slot -> slot.startTime().equals(time)).findFirst().orElseThrow();
    }

    private static WorkingHoursEntry entry(int day, String open, String close) {
        return new WorkingHoursEntry(day, LocalTime.parse(open), LocalTime.parse(close), 30, 3);
    }
}
