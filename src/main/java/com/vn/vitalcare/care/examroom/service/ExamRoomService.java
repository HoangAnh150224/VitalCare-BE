package com.vn.vitalcare.care.examroom.service;

import com.vn.vitalcare.care.examroom.dto.ExamRoomRequest;
import com.vn.vitalcare.care.examroom.dto.RoomBoardResponse;
import com.vn.vitalcare.entity.Appointment;
import com.vn.vitalcare.entity.ExamRoom;
import com.vn.vitalcare.share.web.ListParams;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;

/**
 * A clinic's examination rooms, and the floor they make up: who is in which
 * room and who waits for which doctor.
 *
 * <p>Reads and changes go through the {@code exam_rooms} clinic scope; a room
 * of another clinic answers as not found. Who goes into a room is decided by
 * {@link RoomDispatcher} alone.
 */
public interface ExamRoomService {

    Page<ExamRoom> list(ListParams params);

    List<ExamRoom> getMany(List<Long> ids);

    ExamRoom get(Long id);

    /** A new, free room. The clinic defaults to the caller's own; its code must be new in the clinic. */
    ExamRoom create(ExamRoomRequest request);

    /** Renames a room; its clinic does not change. */
    ExamRoom update(Long id, ExamRoomRequest request);

    /** The clinic's floor now; {@code clinicId} defaults to the caller's own clinic. */
    RoomBoardResponse board(UUID clinicId);

    /**
     * Calls the room's doctor's next patient in, now. Refused, saying why,
     * when the room is paused or not empty, has no doctor this session, or
     * the doctor has nobody waiting.
     */
    RoomBoardResponse callNext(Long roomId);

    /** The patient in the room was called and is not there: back to the queue, behind those not yet called. */
    RoomBoardResponse passOver(Long roomId);

    /** The patient called into the room never came: the visit is a no-show and the room is free for the next. */
    RoomBoardResponse noShow(Long roomId);

    /** The patient in the room has been seen: the visit is completed and the room is free for the next. */
    RoomBoardResponse finish(Long roomId);

    /** No next patient goes in; whoever is inside stays until finished. */
    RoomBoardResponse pause(Long roomId);

    RoomBoardResponse resume(Long roomId);

    /**
     * Frees the room this visit is in, if it is still in it. What a visit
     * ending any other way (an encounter completed, the appointment completed
     * from its own page) calls first. Not scoped: the caller has already
     * checked it may end the visit.
     */
    void releaseFor(Appointment appointment);
}
