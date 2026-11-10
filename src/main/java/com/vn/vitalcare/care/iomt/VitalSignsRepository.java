package com.vn.vitalcare.care.iomt;

import com.vn.vitalcare.entity.VitalSigns;
import org.springframework.data.jpa.repository.JpaRepository;

/** Readings as the intake stores them; what staff and patients read goes through the views of FE-04. */
public interface VitalSignsRepository extends JpaRepository<VitalSigns, Long>,
        org.springframework.data.jpa.repository.JpaSpecificationExecutor<VitalSigns> {

    /** A patient's readings in a window, newest first (FE-04 Vital Sign History). */
    @Override
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = "device")
    org.springframework.data.domain.Page<VitalSigns> findAll(
            org.springframework.data.jpa.domain.Specification<VitalSigns> spec,
            org.springframework.data.domain.Pageable pageable);

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"device", "customer", "session"})
    java.util.Optional<VitalSigns> findWithDetailsById(Long id);

    /**
     * Each patient's latest value of each measure, with when — the band often sends a line with only
     * the temperature, so "the latest reading" alone would hide a heart rate taken a minute earlier.
     * One indexed lookup per patient and measure (customer_id, measured_at desc).
     */
    @org.springframework.data.jpa.repository.Query(nativeQuery = true, value = """
            select c.id as customerId,
                   hr.heart_rate as heartRate, hr.measured_at as heartRateAt,
                   sp.spo2 as spo2, sp.measured_at as spo2At,
                   tp.temperature as skinTemperature, tp.measured_at as skinTemperatureAt,
                   bt.body_temperature as bodyTemperature, bt.measured_at as bodyTemperatureAt,
                   lt.measured_at as lastMeasuredAt,
                   (select count(*) from vital_signs d where d.customer_id = c.id and d.validation_status = 'VALID'
                      and d.deleted_at is null and d.measured_at > now() - interval '24 hours') as readings24h
            from customer c
            left join lateral (select v.heart_rate, v.measured_at from vital_signs v where v.customer_id = c.id
                and v.heart_rate is not null and v.validation_status = 'VALID' and v.deleted_at is null
                order by v.measured_at desc limit 1) hr on true
            left join lateral (select v.spo2, v.measured_at from vital_signs v where v.customer_id = c.id
                and v.spo2 is not null and v.validation_status = 'VALID' and v.deleted_at is null
                order by v.measured_at desc limit 1) sp on true
            left join lateral (select v.temperature, v.measured_at from vital_signs v where v.customer_id = c.id
                and v.temperature is not null and v.validation_status = 'VALID' and v.deleted_at is null
                order by v.measured_at desc limit 1) tp on true
            left join lateral (select v.body_temperature, v.measured_at from vital_signs v where v.customer_id = c.id
                and v.body_temperature is not null and v.validation_status = 'VALID' and v.deleted_at is null
                order by v.measured_at desc limit 1) bt on true
            left join lateral (select v.measured_at from vital_signs v where v.customer_id = c.id
                and v.validation_status = 'VALID' and v.deleted_at is null
                order by v.measured_at desc limit 1) lt on true
            where c.id in (:ids) and lt.measured_at is not null""")
    java.util.List<LatestRow> findLatest(@org.springframework.data.repository.query.Param("ids") java.util.Collection<Long> ids);

    interface LatestRow {
        Long getCustomerId();
        Integer getHeartRate();
        java.time.Instant getHeartRateAt();
        java.math.BigDecimal getSpo2();
        java.time.Instant getSpo2At();
        java.math.BigDecimal getSkinTemperature();
        java.time.Instant getSkinTemperatureAt();
        java.math.BigDecimal getBodyTemperature();
        java.time.Instant getBodyTemperatureAt();
        java.time.Instant getLastMeasuredAt();
        Long getReadings24h();
    }

    /**
     * Valid readings of one patient in buckets of an hour or a day (in the clinic's zone):
     * min / average / max of each measure — the trend charts (FE-04 Trend &amp; Visualization).
     */
    @org.springframework.data.jpa.repository.Query(nativeQuery = true, value = """
            select date_trunc(:unit, v.measured_at at time zone 'Asia/Ho_Chi_Minh') as bucket,
                   count(*) as readings,
                   min(v.heart_rate) as heartRateMin, avg(v.heart_rate) as heartRateAvg, max(v.heart_rate) as heartRateMax,
                   min(v.spo2) as spo2Min, avg(v.spo2) as spo2Avg, max(v.spo2) as spo2Max,
                   min(v.temperature) as skinTemperatureMin, avg(v.temperature) as skinTemperatureAvg,
                   max(v.temperature) as skinTemperatureMax,
                   min(v.body_temperature) as bodyTemperatureMin, avg(v.body_temperature) as bodyTemperatureAvg,
                   max(v.body_temperature) as bodyTemperatureMax
            from vital_signs v
            where v.customer_id = :customerId and v.validation_status = 'VALID' and v.deleted_at is null
              and v.measured_at >= :fromTime and v.measured_at < :toTime
            group by 1 order by 1""")
    java.util.List<TrendRow> findTrend(@org.springframework.data.repository.query.Param("customerId") Long customerId,
                                       @org.springframework.data.repository.query.Param("unit") String unit,
                                       @org.springframework.data.repository.query.Param("fromTime") java.time.OffsetDateTime from,
                                       @org.springframework.data.repository.query.Param("toTime") java.time.OffsetDateTime to);

    interface TrendRow {
        java.time.LocalDateTime getBucket();
        Long getReadings();
        Integer getHeartRateMin();
        java.math.BigDecimal getHeartRateAvg();
        Integer getHeartRateMax();
        java.math.BigDecimal getSpo2Min();
        java.math.BigDecimal getSpo2Avg();
        java.math.BigDecimal getSpo2Max();
        java.math.BigDecimal getSkinTemperatureMin();
        java.math.BigDecimal getSkinTemperatureAvg();
        java.math.BigDecimal getSkinTemperatureMax();
        java.math.BigDecimal getBodyTemperatureMin();
        java.math.BigDecimal getBodyTemperatureAvg();
        java.math.BigDecimal getBodyTemperatureMax();
    }

    boolean existsByDeviceIdAndClientRecordId(Long deviceId, String clientRecordId);

    /** A device's latest readings, newest first, with whose they are. */
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = "customer")
    java.util.List<VitalSigns> findByDeviceIdOrderByMeasuredAtDescIdDesc(Long deviceId,
                                                                         org.springframework.data.domain.Pageable page);
}
