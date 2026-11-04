package com.vn.vitalcare.entity;

import com.vn.vitalcare.share.data.BaseEntity;
import com.vn.vitalcare.share.security.rowlevel.RowLevelResource;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
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
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@RowLevelResource("clinical_encounters")
@Table(name = "clinical_encounter")
public class ClinicalEncounter extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "appointment_id")
    private Appointment appointment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "medical_record_id")
    private MedicalRecord medicalRecord;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id")
    private Employee employee;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "clinic_id")
    private Clinic clinic;

    /** The same column as {@link #clinic}, as a plain value, for row-level policies; see {@code MedicalDevice}. */
    @Column(name = "clinic_id", insertable = false, updatable = false)
    private UUID clinicId;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "ended_at")
    private OffsetDateTime endedAt;

    @Column(name = "chief_complaint", columnDefinition = "text")
    private String chiefComplaint;

    /** What was found on examination, apart from what the patient reported (SRS 3.19 "Thông tin khám"). */
    @Column(name = "examination_findings", columnDefinition = "text")
    private String examinationFindings;

    /** Measured at the visit; null when none were taken. */
    @Embedded
    private VisitVitals vitals;

    /** The doctor's; see {@code clinical_encounters:conclude}. */
    @Column(name = "diagnosis", columnDefinition = "text")
    private String diagnosis;

    @Column(name = "clinical_note", columnDefinition = "text")
    private String clinicalNote;

    /** What the patient is told to do next: when to return, self-care, when to come back sooner. */
    @Column(name = "follow_up", columnDefinition = "text")
    private String followUp;

    /** BF-07: the doctor's conclusion once the service results are in, and what they told the patient. */
    @Column(name = "final_conclusion")
    private String finalConclusion;

    @Column(name = "consultation_notes")
    private String consultationNotes;

    @Column(name = "consulted_at")
    private java.time.OffsetDateTime consultedAt;

    @Column(name = "consulted_by_id")
    private Long consultedById;

    public void consult(String conclusion, String notes, Long by, java.time.OffsetDateTime when) {
        this.finalConclusion = conclusion;
        this.consultationNotes = notes;
        this.consultedById = by;
        this.consultedAt = when;
    }

    public String getFinalConclusion() {
        return finalConclusion;
    }

    public String getConsultationNotes() {
        return consultationNotes;
    }

    public java.time.OffsetDateTime getConsultedAt() {
        return consultedAt;
    }

    public Long getConsultedById() {
        return consultedById;
    }

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private EncounterStatus status = EncounterStatus.IN_PROGRESS;

    public ClinicalEncounter() {
    }

    public Long getId() {
        return id;
    }

    public Appointment getAppointment() {
        return appointment;
    }

    public void setAppointment(Appointment appointment) {
        this.appointment = appointment;
    }

    public MedicalRecord getMedicalRecord() {
        return medicalRecord;
    }

    public void setMedicalRecord(MedicalRecord medicalRecord) {
        this.medicalRecord = medicalRecord;
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

    public OffsetDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(OffsetDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public OffsetDateTime getEndedAt() {
        return endedAt;
    }

    public void setEndedAt(OffsetDateTime endedAt) {
        this.endedAt = endedAt;
    }

    public String getChiefComplaint() {
        return chiefComplaint;
    }

    public void setChiefComplaint(String chiefComplaint) {
        this.chiefComplaint = chiefComplaint;
    }

    public String getDiagnosis() {
        return diagnosis;
    }

    public void setDiagnosis(String diagnosis) {
        this.diagnosis = diagnosis;
    }

    public String getClinicalNote() {
        return clinicalNote;
    }

    public void setClinicalNote(String clinicalNote) {
        this.clinicalNote = clinicalNote;
    }

    public String getFollowUp() {
        return followUp;
    }

    public void setFollowUp(String followUp) {
        this.followUp = followUp;
    }

    public String getExaminationFindings() {
        return examinationFindings;
    }

    public void setExaminationFindings(String examinationFindings) {
        this.examinationFindings = examinationFindings;
    }

    public VisitVitals getVitals() {
        return vitals;
    }

    public void setVitals(VisitVitals vitals) {
        this.vitals = vitals;
    }

    public EncounterStatus getStatus() {
        return status;
    }

    public void setStatus(EncounterStatus status) {
        this.status = status;
    }
}
