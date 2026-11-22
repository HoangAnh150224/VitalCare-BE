package com.vn.vitalcare.care;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.care.alert.AlertController;
import com.vn.vitalcare.care.alert.AlertRules;
import com.vn.vitalcare.care.alert.Alerts;
import com.vn.vitalcare.care.appointment.controller.AppointmentController;
import com.vn.vitalcare.care.appointment.service.AppointmentService;
import com.vn.vitalcare.care.appointment.service.WalkIns;
import com.vn.vitalcare.care.examroom.service.RoomDispatcher;
import com.vn.vitalcare.care.iomt.VitalSignsRepository;
import com.vn.vitalcare.care.results.Consultations;
import com.vn.vitalcare.care.results.Feedback;
import com.vn.vitalcare.care.results.Licenses;
import com.vn.vitalcare.care.results.ResultReturns;
import com.vn.vitalcare.care.results.ResultsController;
import com.vn.vitalcare.care.serviceorder.ServiceOrders;
import com.vn.vitalcare.entity.Alert;
import com.vn.vitalcare.entity.Appointment;
import com.vn.vitalcare.entity.Customer;
import com.vn.vitalcare.entity.Employee;
import com.vn.vitalcare.entity.ExamRoom;
import com.vn.vitalcare.entity.MedicalDevice;
import com.vn.vitalcare.entity.ResultReturn;
import com.vn.vitalcare.entity.VitalSigns;
import com.vn.vitalcare.identity.audit.AuditTrail;
import com.vn.vitalcare.identity.rowlevel.service.RowLevelSecurity;
import com.vn.vitalcare.identity.user.entity.User;
import com.vn.vitalcare.share.exception.FieldValidationException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/** Report-5.1 BE_Infra2: the branching in ResultsController, AlertController and AppointmentController. */
class ClinicControllersUnitTest {

    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-11-25T02:30:00Z");

    @Nested
    @DisplayName("ResultsController — FE-06 result returns, FE-08 feedback")
    class Results {

        private final ResultReturns returns = mock(ResultReturns.class);
        private final Feedback feedback = mock(Feedback.class);
        private final ServiceOrders orders = mock(ServiceOrders.class);
        private final RoomDispatcher rooms = mock(RoomDispatcher.class);
        private final ResultsController controller = new ResultsController(returns, mock(Consultations.class), feedback,
                mock(Licenses.class), orders, rooms);
        private Appointment visit;
        private ResultReturn saved;

        @BeforeEach
        void setUp() {
            User user = mock(User.class);
            when(user.getFullName()).thenReturn("Nguyễn An");
            Employee doctor = new Employee();
            ReflectionTestUtils.setField(doctor, "id", 9L);
            doctor.setUser(user);
            visit = new Appointment();
            ReflectionTestUtils.setField(visit, "id", 10L);
            visit.setEmployee(doctor);
            Customer customer = new Customer();
            ReflectionTestUtils.setField(customer, "id", 1L);
            customer.setFullName("Trần Thị Bích");
            saved = new ResultReturn();
            saved.setAppointment(visit);
            saved.setCustomer(customer);
            saved.setScheduledAt(AT);
            when(returns.scheduleForVisit(any(), any(), any(), any())).thenReturn(saved);
            when(returns.schedule(anyLong(), any(), any())).thenReturn(saved);
            when(returns.scheduleForEncounter(anyLong(), any(), any())).thenReturn(saved);
            when(orders.visitToReturnResultsOf(30L)).thenReturn(visit);
        }

        @Test
        @DisplayName("From a service order with no note: the note names the doctor's room at that time (Vietnam time)")
        void rc01_noteNamesTheRoom() {
            ExamRoom room = new ExamRoom();
            room.setCode("P03");
            when(rooms.roomOfDoctor(eq(9L), eq(java.time.LocalDateTime.of(2026, 11, 25, 9, 30)))).thenReturn(Optional.of(room));
            controller.scheduleFromOrder(30L, new ResultsController.ScheduleRequest(null, null, AT, " "));
            verify(returns).scheduleForVisit(visit, AT, "Quay lại phòng P03 – BS. Nguyễn An nhận kết quả", "phòng P03 – BS. Nguyễn An");
        }

        @Test
        @DisplayName("No room at that time: the note names the doctor's clinic; a note typed by the technician is kept")
        void rc02_noRoomOrOwnNote() {
            when(rooms.roomOfDoctor(anyLong(), any())).thenReturn(Optional.empty());
            controller.scheduleFromOrder(30L, new ResultsController.ScheduleRequest(null, null, AT, null));
            verify(returns).scheduleForVisit(visit, AT, "Quay lại phòng khám của BS. Nguyễn An nhận kết quả", "phòng khám của BS. Nguyễn An");
            controller.scheduleFromOrder(30L, new ResultsController.ScheduleRequest(null, null, AT, "Mang phim"));
            verify(returns).scheduleForVisit(eq(visit), eq(AT), eq("Mang phim"), anyString());
        }

        @Test
        @DisplayName("Boundary: a visit with no doctor gets no place and no note")
        void rc03_noDoctor() {
            visit.setEmployee(null);
            controller.scheduleFromOrder(30L, new ResultsController.ScheduleRequest(null, null, AT, null));
            verify(returns).scheduleForVisit(eq(visit), eq(AT), isNull(), isNull());
        }

        @Test
        @DisplayName("The desk schedules by encounter when given one, else by visit; neither is refused: Choose the visit")
        void rc04_scheduleByEncounterOrVisit() {
            assertThat(controller.schedule(new ResultsController.ScheduleRequest(10L, 20L, AT, null)).customerName()).isEqualTo("Trần Thị Bích");
            verify(returns).scheduleForEncounter(20L, AT, null);
            verify(returns, never()).schedule(anyLong(), any(), any());
            controller.schedule(new ResultsController.ScheduleRequest(10L, null, AT, "n"));
            verify(returns).schedule(10L, AT, "n");
            assertThatThrownBy(() -> controller.schedule(new ResultsController.ScheduleRequest(null, null, AT, null)))
                    .isInstanceOf(FieldValidationException.class).hasMessage("Choose the visit");
        }

        @Test
        @DisplayName("Feedback without a visit is refused before the service is asked")
        void rc05_feedbackNeedsVisit() {
            assertThatThrownBy(() -> controller.give(new ResultsController.FeedbackRequest(null, 5, "Tốt")))
                    .isInstanceOf(FieldValidationException.class).hasMessage("Choose the visit");
            verify(feedback, never()).give(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("AlertController.live — FE-07 alert live readings")
    class AlertLive {

        private final Alerts alerts = mock(Alerts.class);
        private final VitalSignsRepository vitals = mock(VitalSignsRepository.class);
        private final AuditTrail audit = mock(AuditTrail.class);
        private final AlertController controller = new AlertController(mock(AlertRules.class), alerts, vitals, audit);
        private final Alert alert = mock(Alert.class);
        private final Customer patient = new Customer();

        @BeforeEach
        void setUp() {
            ReflectionTestUtils.setField(patient, "id", 1L);
            MedicalDevice band = mock(MedicalDevice.class);
            when(band.getId()).thenReturn(3L);
            when(alert.getDevice()).thenReturn(band);
            when(alert.getCustomer()).thenReturn(patient);
            when(alerts.get(41L)).thenReturn(alert);
        }

        private VitalSigns reading(long id, Customer who, boolean valid) {
            VitalSigns v = mock(VitalSigns.class);
            when(v.getId()).thenReturn(id);
            when(v.getCustomer()).thenReturn(who);
            when(v.isValid()).thenReturn(valid);
            return v;
        }

        @Test
        @DisplayName("Only the valid readings of this alert's patient on the device are shown")
        void al01_onlyThePatientsValidReadings() {
            Customer other = new Customer();
            ReflectionTestUtils.setField(other, "id", 2L);
            List<VitalSigns> rows = List.of(reading(100, patient, true), reading(101, patient, false), reading(102, other, true));
            when(vitals.findByDeviceIdOrderByMeasuredAtDescIdDesc(eq(3L), any())).thenReturn(rows);
            assertThat(controller.live(41L)).extracting(r -> r.id()).containsExactly(100L);
        }

        @Test
        @DisplayName("Watching is recorded once per viewer and alert every 10 minutes, not on every poll")
        void al02_auditedOnce() {
            when(vitals.findByDeviceIdOrderByMeasuredAtDescIdDesc(eq(3L), any())).thenReturn(List.of());
            controller.live(41L);
            controller.live(41L);
            controller.live(41L);
            @SuppressWarnings({"unchecked", "rawtypes"}) ArgumentCaptor<Map> after = ArgumentCaptor.forClass(Map.class);
            verify(audit, times(1)).record(eq("vital_signs"), eq(1L), eq("view"), isNull(), after.capture());
            assertThat(after.getValue().get("what")).isEqualTo("alert 41 live");
        }

        @Test
        @DisplayName("Boundary: an alert with no device has no live readings and nothing is recorded")
        void al03_noDevice() {
            when(alert.getDevice()).thenReturn(null);
            assertThat(controller.live(41L)).isEmpty();
            verify(audit, never()).record(anyString(), any(), anyString(), any(), any());
        }
    }

    @Nested
    @DisplayName("AppointmentController.changeExaminee — FE-10 change who is examined")
    class Examinee {

        private final AppointmentService service = mock(AppointmentService.class);
        private final AppointmentController controller = new AppointmentController(service, mock(RowLevelSecurity.class), mock(WalkIns.class));

        @Test
        @DisplayName("No profile chosen is refused (customerId: Pick a profile) and nothing changes")
        void ap01_needsAProfile() {
            assertThatThrownBy(() -> controller.changeExaminee(10L, Map.of()))
                    .isInstanceOf(FieldValidationException.class).hasMessage("Pick a profile");
            verify(service, never()).changeExaminee(anyLong(), anyLong());
        }
    }
}
