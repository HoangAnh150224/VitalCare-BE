package com.vn.vitalcare.entity;

import com.vn.vitalcare.share.data.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/**
 * One entry of the service catalogue — a test, a scan, a procedure, or the
 * examination itself ({@link ServiceCategory#CONSULTATION}) — with its price.
 *
 * <p>One catalogue for every clinic (the table's {@code clinic_id} stays
 * null). Named apart from the table, {@code clinic_service}, so it does not
 * read like {@code care.clinic.service.ClinicService}, which runs the clinics.
 */
@Entity
@Table(name = "clinic_service")
public class MedicalService extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "service_code", nullable = false, length = 64)
    private String code;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "price", nullable = false, precision = 19, scale = 4)
    private BigDecimal price;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 16)
    private ServiceCategory category = ServiceCategory.LAB;

    /** How long after ordering a result is expected; 0 for the examination itself. */
    @Column(name = "turnaround_minutes", nullable = false)
    private int turnaroundMinutes;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ServiceStatus status = ServiceStatus.ACTIVE;

    public MedicalService() {
    }

    public Long getId() {
        return id;
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

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public ServiceCategory getCategory() {
        return category;
    }

    public void setCategory(ServiceCategory category) {
        this.category = category;
    }

    public int getTurnaroundMinutes() {
        return turnaroundMinutes;
    }

    public void setTurnaroundMinutes(int turnaroundMinutes) {
        this.turnaroundMinutes = turnaroundMinutes;
    }

    public ServiceStatus getStatus() {
        return status;
    }

    public void setStatus(ServiceStatus status) {
        this.status = status;
    }
}
