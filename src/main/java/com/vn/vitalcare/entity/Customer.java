package com.vn.vitalcare.entity;

import com.vn.vitalcare.identity.user.entity.User;
import com.vn.vitalcare.share.data.BaseEntity;
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
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "customer")
public class Customer extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * The account this profile is the owner's own. Null for a dependent — a
     * relative the account's owner registers visits for — who has no sign-in
     * and belongs to {@link #guardian} instead.
     */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", unique = true)
    private User user;

    /** The account owner's own profile, for a dependent; null on that profile itself. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "guardian_id")
    private Customer guardian;

    // A dependent's own name and number: there is no account to hold them.
    // Null on an owner's profile, which reads both from the account.
    @Column(name = "full_name")
    private String fullName;

    @Column(name = "phone")
    private String phone;

    /** What the dependent is to the account's owner: child, parent, spouse, sibling or other. */
    @Column(name = "relationship")
    private String relationship;

    /** Citizen id, passport, or a child's personal identification number. */
    @Column(name = "national_id")
    private String nationalId;

    /** Which of those it is: cccd, personal_id (a child's) or passport. */
    @Column(name = "national_id_type")
    private String nationalIdType;

    @Column(name = "national_id_issued_on")
    private LocalDate nationalIdIssuedOn;

    // The address in the three parts a registration form asks for; "address" is the street part.
    @Column(name = "province")
    private String province;

    @Column(name = "ward")
    private String ward;

    @Column(name = "ethnicity")
    private String ethnicity;

    @Column(name = "nationality")
    private String nationality;

    @Column(name = "occupation")
    private String occupation;

    @Column(name = "customer_code")
    private String customerCode;

    // An owner's profile keeps no phone of its own: users.phone is the sign-in
    // identifier and owns it. A second copy could disagree with the one login
    // reads, and then changing your number here would lock you out.

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Column(name = "gender")
    private String gender;

    @Column(name = "address")
    private String address;

    @Column(name = "emergency_contact_name")
    private String emergencyContactName;

    @Column(name = "emergency_contact_phone")
    private String emergencyContactPhone;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private CustomerStatus status = CustomerStatus.NEUTRAL;

    /**
     * Whether the clinic is still looking after this person: active, inactive
     * or paused (monitoring on hold) — SRS 3.8.5. Separate from {@link #status},
     * which only says whether they have become a patient at all.
     */
    @Column(name = "activity_status", nullable = false)
    private String activityStatus = "active";

    @Column(name = "activity_reason")
    private String activityReason;

    @Column(name = "activity_changed_at")
    private OffsetDateTime activityChangedAt;

    // Bare users.id, like the audit columns.
    @Column(name = "activity_changed_by_id")
    private Long activityChangedById;

    @Column(name = "patient_activated_at")
    private OffsetDateTime patientActivatedAt;

    @Column(name = "patient_activated_by_id")
    private Long patientActivatedById;

    @Enumerated(EnumType.STRING)
    @Column(name = "patient_activation_source")
    private PatientActivationSource patientActivationSource;

    public Customer() {
    }

    /**
     * Makes this customer a patient, recording how, by whom and when.
     *
     * <p>Only called on a {@link CustomerStatus#NEUTRAL} customer; refusing a
     * second activation is the service's job, because only it can say so in
     * an answer the caller understands.
     */
    public void activateAsPatient(PatientActivationSource source, Long actorId, OffsetDateTime when) {
        this.status = CustomerStatus.PATIENT;
        this.patientActivationSource = source;
        this.patientActivatedById = actorId;
        this.patientActivatedAt = when;
    }

    public Long getId() {
        return id;
    }

    /** Records an activity change: to what, why, by whom and when. */
    public void changeActivity(String status, String reason, Long actorId, OffsetDateTime when) {
        this.activityStatus = status;
        this.activityReason = reason;
        this.activityChangedById = actorId;
        this.activityChangedAt = when;
    }

    public String getActivityStatus() {
        return activityStatus;
    }

    public String getActivityReason() {
        return activityReason;
    }

    public OffsetDateTime getActivityChangedAt() {
        return activityChangedAt;
    }

    public Long getActivityChangedById() {
        return activityChangedById;
    }

    public User getUser() {
        return user;
    }

    public Customer getGuardian() {
        return guardian;
    }

    public boolean isDependent() {
        return guardian != null;
    }

    /** Makes this the profile of a relative of {@code guardian}'s account. */
    public void setGuardian(Customer guardian) {
        this.guardian = guardian;
    }

    /** The person's name: their own for a dependent, the account's for its owner. */
    public String getFullName() {
        return fullName != null || user == null ? fullName : user.getFullName();
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    /** The number to reach them on: their own for a dependent, the sign-in number for an owner. */
    public String getPhone() {
        return phone != null || user == null ? phone : user.getPhone();
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getRelationship() {
        return relationship;
    }

    public void setRelationship(String relationship) {
        this.relationship = relationship;
    }

    public String getNationalId() {
        return nationalId;
    }

    public void setNationalId(String nationalId) {
        this.nationalId = nationalId;
    }

    public String getNationalIdType() {
        return nationalIdType;
    }

    public void setNationalIdType(String nationalIdType) {
        this.nationalIdType = nationalIdType;
    }

    public String getProvince() {
        return province;
    }

    public void setProvince(String province) {
        this.province = province;
    }

    public String getWard() {
        return ward;
    }

    public void setWard(String ward) {
        this.ward = ward;
    }

    public LocalDate getNationalIdIssuedOn() {
        return nationalIdIssuedOn;
    }

    public void setNationalIdIssuedOn(LocalDate nationalIdIssuedOn) {
        this.nationalIdIssuedOn = nationalIdIssuedOn;
    }

    public String getEthnicity() {
        return ethnicity;
    }

    public void setEthnicity(String ethnicity) {
        this.ethnicity = ethnicity;
    }

    public String getNationality() {
        return nationality;
    }

    public void setNationality(String nationality) {
        this.nationality = nationality;
    }

    public String getOccupation() {
        return occupation;
    }

    public void setOccupation(String occupation) {
        this.occupation = occupation;
    }

    /** Whether the profile says enough to register a visit: a clinic needs an age and a sex. */
    public boolean isCompleteForVisit() {
        return dateOfBirth != null && gender != null;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public String getCustomerCode() {
        return customerCode;
    }

    public void setCustomerCode(String customerCode) {
        this.customerCode = customerCode;
    }

    public LocalDate getDateOfBirth() {
        return dateOfBirth;
    }

    public void setDateOfBirth(LocalDate dateOfBirth) {
        this.dateOfBirth = dateOfBirth;
    }

    public String getGender() {
        return gender;
    }

    public void setGender(String gender) {
        this.gender = gender;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getEmergencyContactName() {
        return emergencyContactName;
    }

    public void setEmergencyContactName(String emergencyContactName) {
        this.emergencyContactName = emergencyContactName;
    }

    public String getEmergencyContactPhone() {
        return emergencyContactPhone;
    }

    public void setEmergencyContactPhone(String emergencyContactPhone) {
        this.emergencyContactPhone = emergencyContactPhone;
    }

    public CustomerStatus getStatus() {
        return status;
    }

    public OffsetDateTime getPatientActivatedAt() {
        return patientActivatedAt;
    }

    public Long getPatientActivatedById() {
        return patientActivatedById;
    }

    public PatientActivationSource getPatientActivationSource() {
        return patientActivationSource;
    }
}
