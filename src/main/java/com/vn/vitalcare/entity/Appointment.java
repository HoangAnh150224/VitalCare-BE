package com.vn.vitalcare.entity;

import com.vn.vitalcare.share.data.BaseEntity;
import com.vn.vitalcare.share.security.rowlevel.RowLevelResource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@RowLevelResource("appointments")
@Table(name = "appointment")
public class Appointment extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id")
    private Employee employee;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "clinic_id")
    private Clinic clinic;

    /**
     * The same column as {@link #clinic}, as a plain value, for row-level
     * policies: they compare scalar columns, and a clinic is keyed by a UUID
     * the engine's references cannot address. Read-only here; {@link #setClinic}
     * keeps it in step so a policy checking a row before it is saved sees the
     * clinic it is about to have.
     */
    @Column(name = "clinic_id", insertable = false, updatable = false)
    private UUID clinicId;

    @Column(name = "appointment_date")
    private LocalDate appointmentDate;

    @Column(name = "start_time")
    private LocalTime startTime;

    @Column(name = "end_time")
    private LocalTime endTime;

    /**
     * The number called at the doctor's door: per doctor, per day, issued when
     * the visit is registered. {@link #startTime} and {@link #endTime} are
     * about when that number is expected, not a time anybody chose. Null on
     * rows from before visits were registered this way.
     */
    @Column(name = "queue_number")
    private Integer queueNumber;

    @Column(name = "reason")
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private AppointmentStatus status = AppointmentStatus.SCHEDULED;

    /** The code on the appointment slip and in its QR; unique. Set once, at booking. */
    @Column(name = "booking_code", nullable = false, length = 16, unique = true, updatable = false)
    private String bookingCode;

    @Column(name = "note", columnDefinition = "text")
    private String note;

    @Column(name = "checked_in_at")
    private OffsetDateTime checkedInAt;

    // Bare users.id, like the audit columns -- see BaseEntity.
    @Column(name = "checked_in_by_id")
    private Long checkedInById;

    @Column(name = "cancelled_at")
    private OffsetDateTime cancelledAt;

    @Column(name = "cancelled_by_id")
    private Long cancelledById;

    /** Why it was cancelled. Required from the clinic; a customer cancelling their own gives none. */
    @Column(name = "cancel_reason", length = 500)
    private String cancelReason;

    /** The room the visit took place in; kept after it is over, as a record. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "exam_room_id")
    private ExamRoom examRoom;

    @Column(name = "called_at")
    private OffsetDateTime calledAt;

    /**
     * When the patient was last called and not there. Such visits queue behind
     * everybody not yet called, so a no-show does not hold the room.
     */
    @Column(name = "skipped_at")
    private OffsetDateTime skippedAt;

    public Appointment() {
    }

    /** Records the arrival. Only valid from {@link AppointmentStatus#SCHEDULED}; the service checks. */
    public void checkIn(Long actorId, OffsetDateTime when) {
        this.status = AppointmentStatus.CHECKED_IN;
        this.checkedInById = actorId;
        this.checkedInAt = when;
    }

    /** Called into a room. Only valid from {@link AppointmentStatus#CHECKED_IN}; the dispatcher checks. */
    public void callInto(ExamRoom room, OffsetDateTime when) {
        this.status = AppointmentStatus.IN_EXAM;
        this.examRoom = room;
        this.calledAt = when;
    }

    /** Called and not there: back to the queue, behind those not yet called. Only valid from {@link AppointmentStatus#IN_EXAM}. */
    public void passOver(OffsetDateTime when) {
        this.status = AppointmentStatus.CHECKED_IN;
        this.examRoom = null;
        this.calledAt = null;
        this.skippedAt = when;
    }

    /** The visit happened. Only valid from {@link AppointmentStatus#CHECKED_IN} or {@link AppointmentStatus#IN_EXAM}; the service checks. */
    public void complete() {
        this.status = AppointmentStatus.COMPLETED;
    }

    /**
     * The patient never arrived, or was called into the room and never came in.
     * Only valid from {@link AppointmentStatus#SCHEDULED} or {@link AppointmentStatus#IN_EXAM}; the service checks.
     */
    public void markNoShow() {
        this.status = AppointmentStatus.NO_SHOW;
    }

    /** Only valid from {@link AppointmentStatus#SCHEDULED}; the service checks. */
    public void cancel(Long actorId, OffsetDateTime when, String reason) {
        this.status = AppointmentStatus.CANCELLED;
        this.cancelReason = reason;
        this.cancelledById = actorId;
        this.cancelledAt = when;
    }

    public Long getId() {
        return id;
    }

    public Customer getCustomer() {
        return customer;
    }

    public void setCustomer(Customer customer) {
        this.customer = customer;
    }

    public Employee getEmployee() {
        return employee;
    }

    public void setEmployee(Employee employee) {
        this.employee = employee;
    }

    public Clinic getClinic() {
        return clinic;
    }

    public void setClinic(Clinic clinic) {
        this.clinic = clinic;
        this.clinicId = clinic == null ? null : clinic.getClinicId();
    }

    public UUID getClinicId() {
        return clinicId;
    }

    public LocalDate getAppointmentDate() {
        return appointmentDate;
    }

    public void setAppointmentDate(LocalDate appointmentDate) {
        this.appointmentDate = appointmentDate;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalTime startTime) {
        this.startTime = startTime;
    }

    public LocalTime getEndTime() {
        return endTime;
    }

    public void setEndTime(LocalTime endTime) {
        this.endTime = endTime;
    }

    public Integer getQueueNumber() {
        return queueNumber;
    }

    public void setQueueNumber(Integer queueNumber) {
        this.queueNumber = queueNumber;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public AppointmentStatus getStatus() {
        return status;
    }

    public String getBookingCode() {
        return bookingCode;
    }

    public void setBookingCode(String bookingCode) {
        this.bookingCode = bookingCode;
    }

    public Long getCheckedInById() {
        return checkedInById;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public Long getCancelledById() {
        return cancelledById;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public OffsetDateTime getCheckedInAt() {
        return checkedInAt;
    }

    public void setCheckedInAt(OffsetDateTime checkedInAt) {
        this.checkedInAt = checkedInAt;
    }

    public ExamRoom getExamRoom() {
        return examRoom;
    }

    public OffsetDateTime getCalledAt() {
        return calledAt;
    }

    public OffsetDateTime getSkippedAt() {
        return skippedAt;
    }

    public OffsetDateTime getCancelledAt() {
        return cancelledAt;
    }

    public void setCancelledAt(OffsetDateTime cancelledAt) {
        this.cancelledAt = cancelledAt;
    }
}
