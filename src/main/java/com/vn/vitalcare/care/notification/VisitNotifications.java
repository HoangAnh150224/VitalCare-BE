package com.vn.vitalcare.care.notification;

import com.vn.vitalcare.care.appointment.repository.AppointmentRepository;
import com.vn.vitalcare.care.appointment.service.VisitEvents;
import com.vn.vitalcare.care.examroom.service.ExamRoomEvents;
import com.vn.vitalcare.entity.Appointment;
import com.vn.vitalcare.entity.AppointmentStatus;
import com.vn.vitalcare.entity.Customer;
import com.vn.vitalcare.entity.NotificationLevel;
import java.time.format.DateTimeFormatter;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * What a patient's account is told about their visits (FE-13): registered,
 * the fee received, called into a room, the visit concluded. A relative's
 * visit tells the account holder. Heard inside the transaction that made the
 * change, so the notification commits — and is pushed — with it.
 */
@Component
public class VisitNotifications {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final Notifier notifier;
    private final AppointmentRepository appointments;

    public VisitNotifications(Notifier notifier, AppointmentRepository appointments) {
        this.notifier = notifier;
        this.appointments = appointments;
    }

    @EventListener
    public void onRegistered(VisitEvents.Registered event) {
        visit(event.appointmentId()).ifPresent(visit -> tell(visit, NotificationLevel.INFO, "appointment",
                "Đăng ký khám thành công",
                // A visit in the shared line has no doctor yet: the room that calls it in gives one.
                (visit.getEmployee() == null
                        ? "Số khám %s ngày %s. Thanh toán phí khám trước để được xếp hàng chờ khám; bác sĩ được xếp khi gọi vào phòng."
                                .formatted(number(visit), visit.getAppointmentDate().format(DAY))
                        : "Số khám %s với %s ngày %s. Thanh toán phí khám trước để được xếp hàng chờ khám.".formatted(
                                number(visit), doctor(visit), visit.getAppointmentDate().format(DAY))),
                "/my-appointments/show/" + visit.getId()));
    }

    @EventListener
    public void onExamPaid(VisitEvents.ExamPaid event) {
        visit(event.appointmentId()).ifPresent(visit -> tell(visit, NotificationLevel.SUCCESS, "payment",
                "Đã nhận phí khám",
                visit.getStatus() == AppointmentStatus.CHECKED_IN
                        ? "Bạn đã vào hàng chờ khám, theo thời điểm thanh toán."
                        : "Khi tới phòng khám, tiếp nhận tại quầy là bạn vào hàng chờ khám.",
                "/my-appointments/show/" + visit.getId()));
    }

    @EventListener
    public void onCalled(ExamRoomEvents.Called event) {
        visit(event.appointmentId()).ifPresent(visit -> tell(visit, NotificationLevel.WARNING, "appointment",
                "Đến lượt bạn: mời vào phòng " + event.roomCode(),
                "Số khám %s, %s.".formatted(number(visit), doctor(visit)),
                "/my-appointments/show/" + visit.getId()));
    }

    @EventListener
    public void onEnded(VisitEvents.VisitEnded event) {
        visit(event.appointmentId())
                .filter(visit -> visit.getStatus() == AppointmentStatus.COMPLETED && visit.getReferredFrom() == null)
                .ifPresent(visit -> tell(visit, NotificationLevel.SUCCESS, "result",
                        "Lần khám đã hoàn tất", "Xem kết quả khám và hướng dẫn của bác sĩ.",
                        "/my-results/" + visit.getId()));
    }

    private java.util.Optional<Appointment> visit(Long id) {
        return id == null ? java.util.Optional.empty() : appointments.findForNotice(id);
    }

    private void tell(Appointment visit, NotificationLevel level, String type, String title, String body, String link) {
        notifier.notify(accountOf(visit.getCustomer()), level, type, title, body, link);
    }

    /** The account a profile belongs to: its own, or for a relative, the account holder's. */
    public static Long accountOf(Customer customer) {
        if (customer == null) {
            return null;
        }
        if (customer.getUser() != null) {
            return customer.getUser().getId();
        }
        Customer guardian = customer.getGuardian();
        return guardian != null && guardian.getUser() != null ? guardian.getUser().getId() : null;
    }

    private static String number(Appointment visit) {
        return visit.getQueueNumber() == null ? "—" : "%02d".formatted(visit.getQueueNumber());
    }

    private static String doctor(Appointment visit) {
        return visit.getEmployee() == null ? "bác sĩ" : visit.getEmployee().getUser().getFullName();
    }
}
