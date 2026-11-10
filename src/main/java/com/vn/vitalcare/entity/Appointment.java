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
    private AppointmentStatus status = AppointmentStatus.BOOKED;

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

    /**
     * When it came back to its doctor from ordered services or another doctor,
     * or arrived on a referral: such visits are called before everybody else.
     */
    @Column(name = "returned_at")
    private OffsetDateTime returnedAt;

    /**
     * When its examination fee was paid (BF-02): a visit joins its doctor's
     * queue only once it is checked in and paid, and the queue is ordered by
     * this time. Set straight away on a referral, which carries no new fee.
     */
    @Column(name = "paid_at")
    private OffsetDateTime paidAt;

    /** When the patient chose to pay the examination fee at the desk on the day (UC-01 step 4); null: online. */
    @Column(name = "desk_payment_chosen_at")
    private OffsetDateTime deskPaymentChosenAt;

    /** The visit whose doctor sent the patient here the same day; null for an ordinary registration. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "referred_from_id")
    private Appointment referredFrom;

    public Appointment() {
    }

    /**
     * Records the arrival: ready for examination if the fee is already paid
     * (online, before coming), else checked in and waiting to pay (BR-04).
     * Only valid from {@link AppointmentStatus#BOOKED}; the service checks.
     */
    public void checkIn(Long actorId, OffsetDateTime when) {
        this.status = paidAt != null ? AppointmentStatus.READY_FOR_EXAMINATION : AppointmentStatus.CHECKED_IN;
        this.checkedInById = actorId;
        this.checkedInAt = when;
    }

    /** Called into a room. Only valid from {@link AppointmentStatus#READY_FOR_EXAMINATION}; the dispatcher checks. */
    public void callInto(ExamRoom room, OffsetDateTime when) {
        this.status = AppointmentStatus.IN_EXAM;
        this.examRoom = room;
        this.calledAt = when;
    }

    /** Called and not there: back to the queue, behind those not yet called. Only valid from {@link AppointmentStatus#IN_EXAM}. */
    public void passOver(OffsetDateTime when) {
        this.status = AppointmentStatus.READY_FOR_EXAMINATION;
        this.examRoom = null;
        this.calledAt = null;
        this.skippedAt = when;
    }

    /**
     * Out of the room for ordered services or another doctor: the room is the
     * next patient's. Only valid from {@link AppointmentStatus#IN_EXAM}; the service checks.
     */
    public void sendAway() {
        this.status = AppointmentStatus.AWAITING_RESULTS;
        this.examRoom = null;
        this.calledAt = null;
    }

    /** Back in the doctor's queue, ahead of everybody: what it went out for is done. */
    public void returnToQueue(OffsetDateTime when) {
        this.status = AppointmentStatus.READY_FOR_EXAMINATION;
        this.skippedAt = null;
        this.returnedAt = when;
    }

    /** Called ahead of the doctor's other patients, as somebody sent over by a colleague. */
    public void putFirst(OffsetDateTime when) {
        this.returnedAt = when;
    }

    /** The visit happened. Only valid while checked in, in the room or away; the service checks. */
    public void complete() {
        this.status = AppointmentStatus.COMPLETED;
    }

    /**
     * The patient never arrived, or was called into the room and never came in.
     * Only valid from {@link AppointmentStatus#BOOKED} or {@link AppointmentStatus#IN_EXAM}; the service checks.
     */
    public void markNoShow() {
        this.status = AppointmentStatus.NO_SHOW;
    }

    /** Only valid from {@link AppointmentStatus#BOOKED}; the service checks. */
    public void cancel(Long actorId, OffsetDateTime when, String reason) {
        this.status = AppointmentStatus.CANCELLED;
        this.cancelReason = reason;
        this.cancelledById = actorId;
        this.cancelledAt = when;
    }

    public Long getId() {
        return id;
    }

    public OffsetDateTime getReturnedAt() {
        return returnedAt;
    }

    public OffsetDateTime getPaidAt() {
        return paidAt;
    }

    public OffsetDateTime getDeskPaymentChosenAt() {
        return deskPaymentChosenAt;
    }

    /** Pays at the desk on the day, or (null) online after all. The service checks it is still owed. */
    public void setDeskPaymentChosenAt(OffsetDateTime when) {
        this.deskPaymentChosenAt = when;
    }

    /**
     * The examination fee is paid; the first payment counts, a later one
     * changes nothing. Somebody already checked in is ready for examination now (BR-04).
     */
    public void markPaid(OffsetDateTime when) {
        if (this.paidAt == null) {
            this.paidAt = when;
        }
        if (this.status == AppointmentStatus.CHECKED_IN) {
            this.status = AppointmentStatus.READY_FOR_EXAMINATION;
        }
    }

    public Appointment getReferredFrom() {
        return referredFrom;
    }

    public void setReferredFrom(Appointment referredFrom) {
        this.referredFrom = referredFrom;
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
