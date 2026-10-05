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

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(name = "customer_code")
    private String customerCode;

    // No phone of its own: users.phone is the sign-in identifier and owns it.
    // A second copy here could disagree with the one login reads, and then
    // changing your number on this screen would lock you out.

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

    public User getUser() {
        return user;
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
