package com.vn.vitalcare.care.examroom.service;

import java.util.UUID;

/**
 * What happens on a clinic's floor, as Spring events. Each is acted on after
 * the transaction that raised it commits: the dispatcher must see the row the
 * change wrote, and a board must not be told of a change that rolled back.
 */
public final class ExamRoomEvents {

    private ExamRoomEvents() {
    }

    /** Somebody joined a doctor's queue (checked in): the doctor's room may take them now. */
    public record QueueJoined(UUID clinicId, Long doctorId) {
    }

    /** A room can take the next patient: the one in it left, or it was reopened. */
    public record RoomFreed(UUID clinicId, Long roomId) {
    }

    /** Anything on the clinic's board changed; open boards should reload. */
    public record BoardChanged(UUID clinicId) {
    }
}
