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
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * One examination room of a clinic, and who is in it.
 *
 * <p>Which doctor sits in it is not stored here: that follows the doctor's
 * week, each session of which names a room ({@link DoctorWorkingHours}). A
 * patient is put in a room only by the dispatcher, under this row's lock —
 * see {@code RoomDispatcher}.
 */
@Entity
@RowLevelResource("exam_rooms")
@Table(name = "exam_room")
public class ExamRoom extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "clinic_id", nullable = false)
    private Clinic clinic;

    /** The same column as {@link #clinic}, for row-level policies; see {@code Appointment.clinicId}. */
    @Column(name = "clinic_id", insertable = false, updatable = false)
    private UUID clinicId;

    /** What the sign on the door says, such as "P101"; unique within the clinic. */
    @Column(name = "code", nullable = false, length = 20)
    private String code;

    @Column(name = "name", length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ExamRoomStatus status = ExamRoomStatus.AVAILABLE;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "current_appointment_id")
    private Appointment currentAppointment;

    public ExamRoom() {
    }

    /** Somebody is called in. Only valid while {@link ExamRoomStatus#AVAILABLE}; the dispatcher checks. */
    public void occupy(Appointment appointment) {
        this.status = ExamRoomStatus.OCCUPIED;
        this.currentAppointment = appointment;
    }

    /** The patient has left. A paused room stays paused: it was paused while they were in it. */
    public void release() {
        this.currentAppointment = null;
        if (this.status == ExamRoomStatus.OCCUPIED) {
            this.status = ExamRoomStatus.AVAILABLE;
        }
    }

    /** Takes no next patient; whoever is in it now stays until they are done. */
    public void pause() {
        this.status = ExamRoomStatus.PAUSED;
    }

    /** Back in use: occupied if somebody is still inside, free otherwise. */
    public void resume() {
        this.status = currentAppointment == null ? ExamRoomStatus.AVAILABLE : ExamRoomStatus.OCCUPIED;
    }

    public Long getId() {
        return id;
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

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public ExamRoomStatus getStatus() {
        return status;
    }

    public Appointment getCurrentAppointment() {
        return currentAppointment;
    }
}
