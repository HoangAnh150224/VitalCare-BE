package com.vn.vitalcare.care.examroom.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.care.examroom.dto.RoomBoardResponse;
import com.vn.vitalcare.care.examroom.dto.RoomBoardResponse.DoctorRef;
import com.vn.vitalcare.care.examroom.dto.RoomBoardResponse.RoomCard;
import com.vn.vitalcare.care.examroom.dto.RoomBoardResponse.UnpaidVisit;
import com.vn.vitalcare.care.examroom.dto.RoomBoardResponse.Visit;
import com.vn.vitalcare.care.examroom.service.ExamRoomService;
import com.vn.vitalcare.care.staff.service.EmployeeService;
import com.vn.vitalcare.entity.Employee;
import com.vn.vitalcare.entity.ExamRoomStatus;
import com.vn.vitalcare.identity.rowlevel.service.RowLevelSecurity;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** What a doctor's own slice of the board keeps of those still to pay the fee (KAN-152). */
class ExamRoomMineTest {

    private static final DoctorRef ME = new DoctorRef(11L, "Bác sĩ A", "Hô hấp");
    private static final DoctorRef OTHER = new DoctorRef(12L, "Bác sĩ B", "Tim mạch");

    @Test
    @DisplayName("BF-02: an unpaid visit with no doctor shows to the doctor whose room serves its department")
    void unpaidWithoutDoctorFollowsTheRoomsDepartment() {
        ExamRoomService service = mock(ExamRoomService.class);
        EmployeeService employees = mock(EmployeeService.class);
        Employee me = new Employee();
        ReflectionTestUtils.setField(me, "id", 11L);
        when(employees.getForCurrentUser()).thenReturn(me);
        when(service.board(null)).thenReturn(new RoomBoardResponse(UUID.randomUUID(), null,
                List.of(room(4L, "Hô hấp", ME), room(1L, "Tim mạch", OTHER)),
                List.of(),
                List.of(new UnpaidVisit(visit(1L, "Hô hấp"), null),
                        new UnpaidVisit(visit(2L, "Tim mạch"), null),
                        new UnpaidVisit(visit(3L, null), null),
                        new UnpaidVisit(visit(4L, "Tim mạch"), ME),
                        new UnpaidVisit(visit(5L, "Hô hấp"), OTHER)),
                List.of()));

        RoomBoardResponse mine = new ExamRoomController(service, mock(RowLevelSecurity.class), employees).mine();

        // Same department, no department asked, and addressed to me; not another department's, not another doctor's.
        assertEquals(List.of(1L, 3L, 4L),
                mine.awaitingPayment().stream().map(unpaid -> unpaid.visit().appointmentId()).toList());
    }

    private static RoomCard room(Long id, String department, DoctorRef doctor) {
        return new RoomCard(id, "P0" + id, null, department, ExamRoomStatus.AVAILABLE, doctor, null,
                List.of(), List.of(), Map.of());
    }

    private static Visit visit(Long appointmentId, String department) {
        return new Visit(appointmentId, null, 1L, "Bệnh nhân Test", null, null, null, false, false, null, department);
    }
}
