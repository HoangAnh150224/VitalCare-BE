package com.vn.vitalcare.entity;

import com.vn.vitalcare.share.data.BaseEntity;
import com.vn.vitalcare.share.security.rowlevel.RowLevelResource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A member of staff's practising license or certificate (FE-17): who issued
 * it, when, until when, and the scanned proof. Kept by its holder; read by
 * the clinic's administrator, who watches the ones about to expire.
 */
@Entity
@RowLevelResource("licenses")
@Table(name = "professional_license")
public class ProfessionalLicense extends BaseEntity {

    public static final String LICENSE = "LICENSE";
    public static final String CERTIFICATE = "CERTIFICATE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @Column(name = "clinic_id")
    private UUID clinicId;

    @Column(name = "kind", nullable = false)
    private String kind;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "license_number", nullable = false)
    private String licenseNumber;

    @Column(name = "issuer", nullable = false)
    private String issuer;

    @Column(name = "issued_on", nullable = false)
    private LocalDate issuedOn;

    @Column(name = "expires_on")
    private LocalDate expiresOn;

    @Column(name = "file_key")
    private String fileKey;

    @Column(name = "file_name")
    private String fileName;

    @Column(name = "file_type")
    private String fileType;

    @Column(name = "file_size")
    private Long fileSize;

    public ProfessionalLicense() {
    }

    /** VALID, EXPIRING (within {@code warnDays}) or EXPIRED, as of {@code today}. */
    public String validity(LocalDate today, int warnDays) {
        if (expiresOn == null) {
            return "VALID";
        }
        if (expiresOn.isBefore(today)) {
            return "EXPIRED";
        }
        return expiresOn.isBefore(today.plusDays(warnDays + 1L)) ? "EXPIRING" : "VALID";
    }

    public Long getId() {
        return id;
    }

    public Employee getEmployee() {
        return employee;
    }

    public void setEmployee(Employee employee) {
        this.employee = employee;
    }

    public UUID getClinicId() {
        return clinicId;
    }

    public void setClinicId(UUID clinicId) {
        this.clinicId = clinicId;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getLicenseNumber() {
        return licenseNumber;
    }

    public void setLicenseNumber(String licenseNumber) {
        this.licenseNumber = licenseNumber;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public LocalDate getIssuedOn() {
        return issuedOn;
    }

    public void setIssuedOn(LocalDate issuedOn) {
        this.issuedOn = issuedOn;
    }

    public LocalDate getExpiresOn() {
        return expiresOn;
    }

    public void setExpiresOn(LocalDate expiresOn) {
        this.expiresOn = expiresOn;
    }

    public String getFileKey() {
        return fileKey;
    }

    public void setFileKey(String fileKey) {
        this.fileKey = fileKey;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getFileType() {
        return fileType;
    }

    public void setFileType(String fileType) {
        this.fileType = fileType;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }
}
