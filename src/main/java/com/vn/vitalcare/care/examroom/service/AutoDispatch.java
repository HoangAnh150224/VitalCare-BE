package com.vn.vitalcare.care.examroom.service;

import com.vn.vitalcare.care.examroom.config.DispatchConfig;
import com.vn.vitalcare.care.examroom.repository.ExamRoomRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Fills rooms without anybody pressing "call next".
 *
 * <p>Runs on the dispatch pool, after the change that prompted it has
 * committed: a check-in (the doctor's room may be waiting for exactly this
 * person) and a room freeing up (its doctor's next person goes in). A sweep
 * every so often catches what no event announces — a doctor's session
 * starting with people already waiting. Each task is one
 * {@link RoomDispatcher#dispatch}, so they may run in any number and order.
 */
@Component
public class AutoDispatch {

    private static final Logger log = LoggerFactory.getLogger(AutoDispatch.class);

    private final RoomDispatcher dispatcher;
    private final ExamRoomRepository rooms;
    private final TaskExecutor executor;

    public AutoDispatch(RoomDispatcher dispatcher, ExamRoomRepository rooms,
                        @Qualifier(DispatchConfig.EXECUTOR) TaskExecutor executor) {
        this.dispatcher = dispatcher;
        this.rooms = rooms;
        this.executor = executor;
    }

    @Async(DispatchConfig.EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onQueueJoined(ExamRoomEvents.QueueJoined event) {
        dispatcher.roomOfDoctor(event.doctorId(), dispatcher.now())
                .ifPresent(room -> dispatchQuietly(room.getId()));
    }

    @Async(DispatchConfig.EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRoomFreed(ExamRoomEvents.RoomFreed event) {
        dispatchQuietly(event.roomId());
    }

    /**
     * Every so often: close out visits left in a room from an earlier day,
     * then try every free room. A busy floor is mostly handled by the events
     * above. Only finds the work; the dispatch pool does it, not the shared
     * scheduler thread the WebSocket broker's heartbeats also run on.
     */
    @Scheduled(fixedDelayString = "${app.dispatch.sweep-interval:30s}",
            initialDelayString = "${app.dispatch.sweep-interval:30s}")
    public void sweep() {
        for (Long roomId : rooms.findIdsHoldingVisitBefore(dispatcher.now().toLocalDate())) {
            executor.execute(() -> {
                try {
                    dispatcher.closeStale(roomId);
                } catch (RuntimeException e) {
                    log.warn("Closing an earlier day's visit in room {} failed; the next sweep retries it", roomId, e);
                }
            });
        }
        for (Long roomId : rooms.findAvailableIds()) {
            executor.execute(() -> dispatchQuietly(roomId));
        }
    }

    /**
     * A failed attempt is logged and dropped: the next event or sweep tries
     * again, and nothing about the room has changed in the meantime.
     */
    private void dispatchQuietly(Long roomId) {
        try {
            dispatcher.dispatch(roomId);
        } catch (RuntimeException e) {
            log.warn("Dispatching room {} failed; the next sweep retries it", roomId, e);
        }
    }
}
