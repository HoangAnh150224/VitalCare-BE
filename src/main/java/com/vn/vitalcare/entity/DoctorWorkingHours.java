package com.vn.vitalcare.entity;

import com.vn.vitalcare.share.data.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.DayOfWeek;
import java.time.LocalTime;

/**
 * One session of one weekday in one doctor's week — "Mondays 08:00–12:00,
 * half an hour a patient" — repeating every week. A weekday without a row is
 * a day the doctor does not see patients.
 *
 * <p>The sessions say how many patients a day holds: each is cut into visits
 * of {@code visitMinutes}, and a day's queue numbers run up to that count.
 * See {@code DoctorAvailabilityService}.
 */
@Entity
@Table(name = "doctor_working_hours")
public class DoctorWorkingHours extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    /** ISO numbering, as {@link DayOfWeek#getValue()}: 1 = Monday … 7 = Sunday. */
    @Column(name = "day_of_week", nullable = false)
    private short dayOfWeek;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Column(name = "visit_minutes", nullable = false)
    private int visitMinutes;

    /** The room the doctor sits in for this session; null when none is set. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "exam_room_id")
    private ExamRoom examRoom;

    public DoctorWorkingHours() {
    }

    /** A session with no room set. */
    public DoctorWorkingHours(Employee employee, DayOfWeek day, LocalTime startTime, LocalTime endTime,
                              int visitMinutes) {
        this(employee, day, startTime, endTime, visitMinutes, null);
    }

    public DoctorWorkingHours(Employee employee, DayOfWeek day, LocalTime startTime, LocalTime endTime,
                              int visitMinutes, ExamRoom examRoom) {
        this.employee = employee;
        this.examRoom = examRoom;
        this.dayOfWeek = (short) day.getValue();
        this.startTime = startTime;
        this.endTime = endTime;
        this.visitMinutes = visitMinutes;
    }

    public Long getId() {
        return id;
    }

    public Employee getEmployee() {
        return employee;
    }

    public DayOfWeek getDayOfWeek() {
        return DayOfWeek.of(dayOfWeek);
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public LocalTime getEndTime() {
        return endTime;
    }

    public int getVisitMinutes() {
        return visitMinutes;
    }

    public ExamRoom getExamRoom() {
        return examRoom;
    }
}
