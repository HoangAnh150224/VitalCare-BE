package com.vn.vitalcare.care.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.care.appointment.repository.AppointmentRepository;
import com.vn.vitalcare.care.appointment.service.VisitEvents;
import com.vn.vitalcare.care.examroom.service.ExamRoomEvents;
import com.vn.vitalcare.entity.Appointment;
import com.vn.vitalcare.entity.AppointmentStatus;
import com.vn.vitalcare.entity.Customer;
import com.vn.vitalcare.entity.Employee;
import com.vn.vitalcare.entity.NotificationLevel;
import com.vn.vitalcare.identity.user.entity.User;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** FE-13 Notification Center / FE-10 Appointment: what the patient is told at each step of a visit. Report-5.1, sheet BE_Gaps. */
class VisitNotificationsUnitTest {

    private Notifier notifier;
    private AppointmentRepository appointments;
    private VisitNotifications notices;
    private Appointment visit;

    private static User account(long id, String name) {
        User u = mock(User.class);
        when(u.getId()).thenReturn(id);
        when(u.getFullName()).thenReturn(name);
        return u;
    }

    @BeforeEach
    void setUp() {
        notifier = mock(Notifier.class);
        appointments = mock(AppointmentRepository.class);
        notices = new VisitNotifications(notifier, appointments);
        Customer patient = new Customer();
        patient.setUser(account(42L, "Trần Thị Bích"));
        visit = new Appointment();
        ReflectionTestUtils.setField(visit, "id", 10L);
        visit.setCustomer(patient);
        visit.setAppointmentDate(LocalDate.of(2026, 11, 25));
        visit.setQueueNumber(7);
        when(appointments.findForNotice(10L)).thenReturn(Optional.of(visit));
    }

    private void status(AppointmentStatus s) {
        ReflectionTestUtils.setField(visit, "status", s);
    }

    @Test void vn01_registeredInTheSharedLine() {
        notices.onRegistered(new VisitEvents.Registered(10L));
        verify(notifier).notify(42L, NotificationLevel.INFO, "appointment", "Đăng ký khám thành công",
                "Số khám 07 ngày 25/11/2026. Thanh toán phí khám trước để được xếp hàng chờ khám; bác sĩ được xếp khi gọi vào phòng.",
                "/my-appointments/show/10");
    }

    @Test void vn02_registeredWithADoctor_namesThem() {
        Employee doctor = new Employee();
        doctor.setUser(account(9L, "BS. Nguyễn An"));
        visit.setEmployee(doctor);
        notices.onRegistered(new VisitEvents.Registered(10L));
        verify(notifier).notify(eq(42L), eq(NotificationLevel.INFO), eq("appointment"), eq("Đăng ký khám thành công"),
                eq("Số khám 07 với BS. Nguyễn An ngày 25/11/2026. Thanh toán phí khám trước để được xếp hàng chờ khám."), anyString());
    }

    @Test void vn03_checkedInUnpaid_askedToPay_paid_inTheLine() {
        status(AppointmentStatus.CHECKED_IN);
        notices.onCheckedIn(new VisitEvents.CheckedIn(10L));
        verify(notifier).notify(eq(42L), any(), eq("appointment"), eq("Đã tiếp nhận tại quầy"),
                eq("Số khám 07: vui lòng trả phí khám tại quầy để vào hàng chờ khám."), anyString());
        status(AppointmentStatus.READY_FOR_EXAMINATION);
        notices.onCheckedIn(new VisitEvents.CheckedIn(10L));
        verify(notifier).notify(eq(42L), any(), eq("appointment"), eq("Đã tiếp nhận tại quầy"),
                eq("Số khám 07: bạn đã vào hàng chờ khám, vui lòng chờ được gọi vào phòng."), anyString());
    }

    @Test void vn04_examPaid_beforeAndAfterCheckIn() {
        status(AppointmentStatus.BOOKED);
        notices.onExamPaid(new VisitEvents.ExamPaid(10L));
        verify(notifier).notify(eq(42L), eq(NotificationLevel.SUCCESS), eq("payment"), eq("Đã nhận phí khám"),
                eq("Khi tới phòng khám, tiếp nhận tại quầy là bạn vào hàng chờ khám."), anyString());
    }

    @Test void vn05_calledIntoARoom() {
        notices.onCalled(new ExamRoomEvents.Called(10L, "P101"));
        verify(notifier).notify(eq(42L), eq(NotificationLevel.WARNING), eq("appointment"),
                eq("Đến lượt bạn: mời vào phòng P101"), eq("Số khám 07, bác sĩ."), anyString());
    }

    @Test void vn06_completedVisitPointsToItsResult_aReferralSaysNothing() {
        status(AppointmentStatus.COMPLETED);
        notices.onEnded(new VisitEvents.VisitEnded(10L));
        verify(notifier).notify(42L, NotificationLevel.SUCCESS, "result", "Lần khám đã hoàn tất",
                "Xem kết quả khám và hướng dẫn của bác sĩ.", "/my-results/10");
        Notifier quiet = mock(Notifier.class);
        visit.setReferredFrom(new Appointment());
        new VisitNotifications(quiet, appointments).onEnded(new VisitEvents.VisitEnded(10L));
        verifyNoInteractions(quiet);
    }

    @Test void vn07_aRelativeWithoutAnAccount_theGuardianIsTold_noIdNothing() {
        Customer guardian = new Customer();
        guardian.setUser(account(50L, "Chủ tài khoản"));
        Customer child = new Customer();
        child.setGuardian(guardian);
        assertEquals(50L, VisitNotifications.accountOf(child));
        assertNull(VisitNotifications.accountOf(new Customer()));
        assertNull(VisitNotifications.accountOf(null));
        notices.onRegistered(new VisitEvents.Registered(null));
        verifyNoInteractions(appointments);
    }
}
