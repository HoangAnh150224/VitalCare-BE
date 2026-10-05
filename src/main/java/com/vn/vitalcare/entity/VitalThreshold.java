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
import java.math.BigDecimal;

@Entity
@Table(name = "vital_threshold")
public class VitalThreshold extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "clinic_id")
    private Clinic clinic;

    @Column(name = "heart_rate_min")
    private Integer heartRateMin;

    @Column(name = "heart_rate_max")
    private Integer heartRateMax;

    @Column(name = "spo2_min")
    private BigDecimal spo2Min;

    @Column(name = "temperature_min")
    private BigDecimal temperatureMin;

    @Column(name = "temperature_max")
    private BigDecimal temperatureMax;

    @Column(name = "systolic_bp_min")
    private Integer systolicBpMin;

    @Column(name = "systolic_bp_max")
    private Integer systolicBpMax;

    @Column(name = "diastolic_bp_min")
    private Integer diastolicBpMin;

    @Column(name = "diastolic_bp_max")
    private Integer diastolicBpMax;

    @Column(name = "respiratory_rate_min")
    private Integer respiratoryRateMin;

    @Column(name = "respiratory_rate_max")
    private Integer respiratoryRateMax;

    public VitalThreshold() {
    }

    public Long getId() {
        return id;
    }

    public Clinic getClinic() {
        return clinic;
    }

    public void setClinic(Clinic clinic) {
        this.clinic = clinic;
    }

    public Integer getHeartRateMin() {
        return heartRateMin;
    }

    public void setHeartRateMin(Integer heartRateMin) {
        this.heartRateMin = heartRateMin;
    }

    public Integer getHeartRateMax() {
        return heartRateMax;
    }

    public void setHeartRateMax(Integer heartRateMax) {
        this.heartRateMax = heartRateMax;
    }

    public BigDecimal getSpo2Min() {
        return spo2Min;
    }

    public void setSpo2Min(BigDecimal spo2Min) {
        this.spo2Min = spo2Min;
    }

    public BigDecimal getTemperatureMin() {
        return temperatureMin;
    }

    public void setTemperatureMin(BigDecimal temperatureMin) {
        this.temperatureMin = temperatureMin;
    }

    public BigDecimal getTemperatureMax() {
        return temperatureMax;
    }

    public void setTemperatureMax(BigDecimal temperatureMax) {
        this.temperatureMax = temperatureMax;
    }

    public Integer getSystolicBpMin() {
        return systolicBpMin;
    }

    public void setSystolicBpMin(Integer systolicBpMin) {
        this.systolicBpMin = systolicBpMin;
    }

    public Integer getSystolicBpMax() {
        return systolicBpMax;
    }

    public void setSystolicBpMax(Integer systolicBpMax) {
        this.systolicBpMax = systolicBpMax;
    }

    public Integer getDiastolicBpMin() {
        return diastolicBpMin;
    }

    public void setDiastolicBpMin(Integer diastolicBpMin) {
        this.diastolicBpMin = diastolicBpMin;
    }

    public Integer getDiastolicBpMax() {
        return diastolicBpMax;
    }

    public void setDiastolicBpMax(Integer diastolicBpMax) {
        this.diastolicBpMax = diastolicBpMax;
    }

    public Integer getRespiratoryRateMin() {
        return respiratoryRateMin;
    }

    public void setRespiratoryRateMin(Integer respiratoryRateMin) {
        this.respiratoryRateMin = respiratoryRateMin;
    }

    public Integer getRespiratoryRateMax() {
        return respiratoryRateMax;
    }

    public void setRespiratoryRateMax(Integer respiratoryRateMax) {
        this.respiratoryRateMax = respiratoryRateMax;
    }
}
